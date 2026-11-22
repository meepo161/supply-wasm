package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApprovalRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateApprovals()
    }

    @Test
    fun `sequential route approves demand via adapter and blocks direct approve`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "appr-creator", "password", "Appr Creator", starterPermissions())
        val approver1 = createUserWithRole(admin, "appr-one", "password", "Approver One", emptyList())
        val approver2 = createUserWithRole(admin, "appr-two", "password", "Approver Two", emptyList())
        createUserWithRole(admin, "appr-direct", "password", "Direct Approver", directApproverPermissions())
        val creatorClient = loginClient("appr-creator", "password")
        val adminClient = loginClient("admin", "admin123")

        val template = createTemplate(
            adminClient, "DEMAND-SEQ",
            listOf(
                ApprovalStageInput(1, "Lead", ApprovalDecisionPolicy.ALL, listOf(userAssignee(approver1.id))),
                ApprovalStageInput(2, "Head", ApprovalDecisionPolicy.ANY, listOf(userAssignee(approver2.id)))
            )
        )

        val demand = createSubmittedDemand(creatorClient)

        val started = startRoute(creatorClient, template.id, demand.header.id)
        assertEquals(HttpStatusCode.Created, started.status, started.bodyAsText())
        val route = started.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!
        assertEquals(ApprovalRouteStatus.ACTIVE, route.status)
        assertEquals(1, route.currentStageNo)
        assertEquals(demand.header.version, route.targetVersion)
        assertEquals(ApprovalStageStatus.ACTIVE, route.stages[0].status)
        assertEquals(ApprovalStageStatus.PENDING, route.stages[1].status)

        val directApproverClient = loginClient("appr-direct", "password")
        val directApprove = directApproverClient.post("/api/demands/${demand.header.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ApproveDemandRequest(demand.header.version)))
        }
        assertEquals(HttpStatusCode.Conflict, directApprove.status, directApprove.bodyAsText())

        val wrongStage = decide(loginClient("appr-two", "password"), route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.Forbidden, wrongStage.status, wrongStage.bodyAsText())

        val first = decide(loginClient("appr-one", "password"), route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val afterFirst = first.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!
        assertEquals(ApprovalRouteStatus.ACTIVE, afterFirst.status)
        assertEquals(2, afterFirst.currentStageNo)
        assertEquals(ApprovalStageStatus.APPROVED, afterFirst.stages[0].status)
        assertEquals(ApprovalStageStatus.ACTIVE, afterFirst.stages[1].status)
        assertEquals(1, afterFirst.decisions.size)
        assertEquals(approver1.id, afterFirst.decisions.single().decidedBy)
        assertNotNull(afterFirst.decisions.single().createdAt)

        val second = decide(loginClient("appr-two", "password"), route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        val completed = second.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!
        assertEquals(ApprovalRouteStatus.COMPLETED, completed.status)
        assertNotNull(completed.completedAt)

        val demandAfter = creatorClient.get("/api/demands/${demand.header.id}")
            .bodyAs<ApiResponse<DemandDetailDto>>().data!!
        assertEquals(DemandStatus.APPROVED, demandAfter.header.status)
        assertEquals(approver2.id, demandAfter.header.approvedBy)
    }

    @Test
    fun `reject requires comment and terminates route without approving demand`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "rej-creator", "password", "Rej Creator", starterPermissions())
        val approver = createUserWithRole(admin, "rej-approver", "password", "Rej Approver", emptyList())
        val creatorClient = loginClient("rej-creator", "password")
        val adminClient = loginClient("admin", "admin123")
        val template = createTemplate(
            adminClient, "DEMAND-REJ",
            listOf(ApprovalStageInput(1, "Single", ApprovalDecisionPolicy.ALL, listOf(userAssignee(approver.id))))
        )
        val demand = createSubmittedDemand(creatorClient)
        val route = startRoute(creatorClient, template.id, demand.header.id)
            .bodyAs<ApiResponse<ApprovalRouteDto>>().data!!

        val approverClient = loginClient("rej-approver", "password")
        val noComment = decide(approverClient, route.id, ApprovalDecision.REJECT, comment = null)
        assertEquals(HttpStatusCode.BadRequest, noComment.status, noComment.bodyAsText())

        val rejected = decide(approverClient, route.id, ApprovalDecision.REJECT, comment = "Not justified")
        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        val rejectedRoute = rejected.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!
        assertEquals(ApprovalRouteStatus.REJECTED, rejectedRoute.status)
        assertEquals(ApprovalStageStatus.REJECTED, rejectedRoute.stages.single().status)
        assertEquals("Not justified", rejectedRoute.decisions.single().comment)

        val demandAfter = creatorClient.get("/api/demands/${demand.header.id}")
            .bodyAs<ApiResponse<DemandDetailDto>>().data!!
        assertEquals(DemandStatus.PENDING_APPROVAL, demandAfter.header.status)
    }

    @Test
    fun `second active route rejected and start is idempotent by key user and content`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "idem-creator", "password", "Idem Creator", starterPermissions())
        val approver = createUserWithRole(admin, "idem-approver", "password", "Idem Approver", emptyList())
        val creatorClient = loginClient("idem-creator", "password")
        val adminClient = loginClient("admin", "admin123")
        val template = createTemplate(
            adminClient, "DEMAND-IDEM",
            listOf(ApprovalStageInput(1, "Single", ApprovalDecisionPolicy.ALL, listOf(userAssignee(approver.id))))
        )
        val demand = createSubmittedDemand(creatorClient)
        val request = StartApprovalRouteRequest(template.id, "DEMAND", demand.header.id, idempotencyKey = "start-1")

        val first = startRoute(creatorClient, request)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val firstId = first.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!.id

        val replay = startRoute(creatorClient, request)
        assertEquals(HttpStatusCode.Created, replay.status, replay.bodyAsText())
        assertEquals(firstId, replay.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!.id)

        val otherTemplate = createTemplate(
            adminClient, "DEMAND-IDEM-2",
            listOf(ApprovalStageInput(1, "Single", ApprovalDecisionPolicy.ANY, listOf(userAssignee(approver.id))))
        )
        val changed = startRoute(creatorClient, request.copy(templateId = otherTemplate.id))
        assertEquals(HttpStatusCode.Conflict, changed.status, changed.bodyAsText())

        val withoutKey = startRoute(
            creatorClient,
            StartApprovalRouteRequest(template.id, "DEMAND", demand.header.id)
        )
        assertEquals(HttpStatusCode.Conflict, withoutKey.status, withoutKey.bodyAsText())
    }

    @Test
    fun `stale target version rejects decision`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "stale-creator", "password", "Stale Creator", starterPermissions())
        val approver = createUserWithRole(admin, "stale-approver", "password", "Stale Approver", emptyList())
        val creatorClient = loginClient("stale-creator", "password")
        val adminClient = loginClient("admin", "admin123")
        val template = createTemplate(
            adminClient, "DEMAND-STALE",
            listOf(ApprovalStageInput(1, "Single", ApprovalDecisionPolicy.ALL, listOf(userAssignee(approver.id))))
        )
        val demand = createSubmittedDemand(creatorClient)
        val route = startRoute(creatorClient, template.id, demand.header.id)
            .bodyAs<ApiResponse<ApprovalRouteDto>>().data!!

        bumpDemandVersion(demand.header.id)

        val response = decide(loginClient("stale-approver", "password"), route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
    }

    @Test
    fun `parallel stage ALL requires every assignee and forbids double decision`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "all-creator", "password", "All Creator", starterPermissions())
        val first = createUserWithRole(admin, "all-first", "password", "All First", emptyList())
        val second = createUserWithRole(admin, "all-second", "password", "All Second", emptyList())
        val creatorClient = loginClient("all-creator", "password")
        val adminClient = loginClient("admin", "admin123")
        val template = createTemplate(
            adminClient, "DEMAND-ALL",
            listOf(
                ApprovalStageInput(
                    1, "Parallel", ApprovalDecisionPolicy.ALL,
                    listOf(userAssignee(first.id), userAssignee(second.id))
                )
            )
        )
        val demand = createSubmittedDemand(creatorClient)
        val route = startRoute(creatorClient, template.id, demand.header.id)
            .bodyAs<ApiResponse<ApprovalRouteDto>>().data!!

        val firstClient = loginClient("all-first", "password")
        val firstDecision = decide(firstClient, route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.OK, firstDecision.status, firstDecision.bodyAsText())
        assertEquals(ApprovalRouteStatus.ACTIVE, firstDecision.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!.status)

        val duplicate = decide(firstClient, route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.Conflict, duplicate.status, duplicate.bodyAsText())

        val secondDecision = decide(loginClient("all-second", "password"), route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.OK, secondDecision.status, secondDecision.bodyAsText())
        assertEquals(ApprovalRouteStatus.COMPLETED, secondDecision.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!.status)

        val demandAfter = creatorClient.get("/api/demands/${demand.header.id}")
            .bodyAs<ApiResponse<DemandDetailDto>>().data!!
        assertEquals(DemandStatus.APPROVED, demandAfter.header.status)
    }

    @Test
    fun `parallel stage ANY completes on first decision`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "any-creator", "password", "Any Creator", starterPermissions())
        val first = createUserWithRole(admin, "any-first", "password", "Any First", emptyList())
        val second = createUserWithRole(admin, "any-second", "password", "Any Second", emptyList())
        val creatorClient = loginClient("any-creator", "password")
        val adminClient = loginClient("admin", "admin123")
        val template = createTemplate(
            adminClient, "DEMAND-ANY",
            listOf(
                ApprovalStageInput(
                    1, "Parallel", ApprovalDecisionPolicy.ANY,
                    listOf(userAssignee(first.id), userAssignee(second.id))
                )
            )
        )
        val demand = createSubmittedDemand(creatorClient)
        val route = startRoute(creatorClient, template.id, demand.header.id)
            .bodyAs<ApiResponse<ApprovalRouteDto>>().data!!

        val decision = decide(loginClient("any-first", "password"), route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.OK, decision.status, decision.bodyAsText())
        assertEquals(ApprovalRouteStatus.COMPLETED, decision.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!.status)

        val late = decide(loginClient("any-second", "password"), route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.Conflict, late.status, late.bodyAsText())
    }

    @Test
    fun `route cancellation by right unlocks demand cancellation`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "cxl-creator", "password", "Cxl Creator", starterPermissions())
        val approver = createUserWithRole(admin, "cxl-approver", "password", "Cxl Approver", emptyList())
        createUserWithRole(admin, "cxl-manager", "password", "Cxl Manager", listOf(CreatePermissionRequest("approvals", "cancel")))
        createUserWithRole(admin, "cxl-plain", "password", "Cxl Plain", emptyList())
        val creatorClient = loginClient("cxl-creator", "password")
        val adminClient = loginClient("admin", "admin123")
        val template = createTemplate(
            adminClient, "DEMAND-CXL",
            listOf(ApprovalStageInput(1, "Single", ApprovalDecisionPolicy.ALL, listOf(userAssignee(approver.id))))
        )
        val demand = createSubmittedDemand(creatorClient)
        val route = startRoute(creatorClient, template.id, demand.header.id)
            .bodyAs<ApiResponse<ApprovalRouteDto>>().data!!

        val directCancel = creatorClient.post("/api/demands/${demand.header.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelDemandRequest(demand.header.version, "changed mind")))
        }
        assertEquals(HttpStatusCode.Conflict, directCancel.status, directCancel.bodyAsText())

        val forbiddenCancel = cancelRoute(loginClient("cxl-plain", "password"), route.id, "no right")
        assertEquals(HttpStatusCode.Forbidden, forbiddenCancel.status, forbiddenCancel.bodyAsText())

        val cancelled = cancelRoute(loginClient("cxl-manager", "password"), route.id, "obsolete")
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        val cancelledRoute = cancelled.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!
        assertEquals(ApprovalRouteStatus.CANCELLED, cancelledRoute.status)
        assertTrue(cancelledRoute.stages.all { it.status == ApprovalStageStatus.CANCELLED })

        val demandCancel = creatorClient.post("/api/demands/${demand.header.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelDemandRequest(demand.header.version, "changed mind")))
        }
        assertEquals(HttpStatusCode.OK, demandCancel.status, demandCancel.bodyAsText())
        assertEquals(
            DemandStatus.CANCELLED,
            demandCancel.bodyAs<ApiResponse<DemandDetailDto>>().data!!.header.status
        )
    }

    @Test
    fun `template requires explicit decision policy and supported target type`() = testApp {
        val admin = jsonClient()
        val approver = createUserWithRole(admin, "tmpl-approver", "password", "Tmpl Approver", emptyList())
        val adminClient = loginClient("admin", "admin123")

        val noPolicy = createTemplateRaw(
            adminClient, "DEMAND-NO-POLICY", "DEMAND",
            listOf(ApprovalStageInput(1, "Single", decisionPolicy = null, assignees = listOf(userAssignee(approver.id))))
        )
        assertEquals(HttpStatusCode.BadRequest, noPolicy.status, noPolicy.bodyAsText())

        val unsupported = createTemplateRaw(
            adminClient, "BOM-TEMPLATE", "BOM",
            listOf(ApprovalStageInput(1, "Single", ApprovalDecisionPolicy.ALL, listOf(userAssignee(approver.id))))
        )
        assertEquals(HttpStatusCode.BadRequest, unsupported.status, unsupported.bodyAsText())
    }

    @Test
    fun `manual assignee management by right with auto advance on removal`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "asg-creator", "password", "Asg Creator", starterPermissions())
        val approver = createUserWithRole(admin, "asg-approver", "password", "Asg Approver", emptyList())
        val extra = createUserWithRole(admin, "asg-extra", "password", "Asg Extra", emptyList())
        createUserWithRole(admin, "asg-manager", "password", "Asg Manager", listOf(CreatePermissionRequest("approvals", "assign")))
        createUserWithRole(admin, "asg-plain", "password", "Asg Plain", emptyList())
        val creatorClient = loginClient("asg-creator", "password")
        val adminClient = loginClient("admin", "admin123")
        val template = createTemplate(
            adminClient, "DEMAND-ASG",
            listOf(ApprovalStageInput(1, "Single", ApprovalDecisionPolicy.ALL, listOf(userAssignee(approver.id))))
        )
        val demand = createSubmittedDemand(creatorClient)
        val route = startRoute(creatorClient, template.id, demand.header.id)
            .bodyAs<ApiResponse<ApprovalRouteDto>>().data!!
        val stageId = route.stages.single().id

        val managerClient = loginClient("asg-manager", "password")
        val forbiddenAdd = addAssignee(loginClient("asg-plain", "password"), route.id, stageId, extra.id)
        assertEquals(HttpStatusCode.Forbidden, forbiddenAdd.status, forbiddenAdd.bodyAsText())

        val added = addAssignee(managerClient, route.id, stageId, extra.id)
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
        val withExtra = added.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!
        assertEquals(2, withExtra.stages.single().assignees.size)
        val extraAssigneeId = withExtra.stages.single().assignees.first { it.userId == extra.id }.id

        val decision = decide(loginClient("asg-approver", "password"), route.id, ApprovalDecision.APPROVE)
        assertEquals(HttpStatusCode.OK, decision.status, decision.bodyAsText())
        assertEquals(ApprovalRouteStatus.ACTIVE, decision.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!.status)

        val removed = managerClient.delete("/api/approvals/routes/${route.id}/stages/$stageId/assignees/$extraAssigneeId")
        assertEquals(HttpStatusCode.OK, removed.status, removed.bodyAsText())
        val afterRemoval = removed.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!
        assertEquals(ApprovalRouteStatus.COMPLETED, afterRemoval.status)

        val demandAfter = creatorClient.get("/api/demands/${demand.header.id}")
            .bodyAs<ApiResponse<DemandDetailDto>>().data!!
        assertEquals(DemandStatus.APPROVED, demandAfter.header.status)
    }

    @Test
    fun `route read requires access to target`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(admin, "read-creator", "password", "Read Creator", starterPermissions())
        val approver = createUserWithRole(admin, "read-approver", "password", "Read Approver", emptyList())
        createUserWithRole(admin, "read-stranger", "password", "Read Stranger", emptyList())
        val creatorClient = loginClient("read-creator", "password")
        val adminClient = loginClient("admin", "admin123")
        val template = createTemplate(
            adminClient, "DEMAND-READ",
            listOf(ApprovalStageInput(1, "Single", ApprovalDecisionPolicy.ALL, listOf(userAssignee(approver.id))))
        )
        val demand = createSubmittedDemand(creatorClient)
        val route = startRoute(creatorClient, template.id, demand.header.id)
            .bodyAs<ApiResponse<ApprovalRouteDto>>().data!!

        val strangerResponse = loginClient("read-stranger", "password").get("/api/approvals/routes/${route.id}")
        assertEquals(HttpStatusCode.Forbidden, strangerResponse.status)

        val creatorResponse = creatorClient.get("/api/approvals/routes/${route.id}")
        assertEquals(HttpStatusCode.OK, creatorResponse.status, creatorResponse.bodyAsText())

        val listResponse = creatorClient.get("/api/approvals/routes?targetType=DEMAND&targetId=${demand.header.id}")
        assertEquals(HttpStatusCode.OK, listResponse.status, listResponse.bodyAsText())
        assertEquals(1, listResponse.bodyAs<ApiResponse<List<ApprovalRouteDto>>>().data!!.size)
    }

    private fun starterPermissions() = listOf(
        CreatePermissionRequest("demands", "create"),
        CreatePermissionRequest("demands", "read_any"),
        CreatePermissionRequest("demands", "update"),
        CreatePermissionRequest("approvals", "start"),
        CreatePermissionRequest("wms", "manage")
    )

    private fun directApproverPermissions() = listOf(
        CreatePermissionRequest("demands", "read_any"),
        CreatePermissionRequest("demands", "approve")
    )

    private fun userAssignee(userId: Long) = ApprovalAssigneeInput(ApprovalAssigneeType.USER, userId = userId)

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>
    ): UserDto {
        val bootstrapResponse = adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        if (bootstrapResponse.status != HttpStatusCode.Created && bootstrapResponse.status != HttpStatusCode.Unauthorized) {
            assertEquals(HttpStatusCode.Created, bootstrapResponse.status, bootstrapResponse.bodyAsText())
        }
        val loginResponse = adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())

        val roleResponse = adminClient.post("/api/roles") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateRoleRequest(login.uppercase(), fullName, permissions = permissions)))
        }
        assertEquals(HttpStatusCode.Created, roleResponse.status, roleResponse.bodyAsText())
        val role = roleResponse.bodyAs<ApiResponse<RoleDto>>().data!!

        val userResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest(login, password, fullName, roleIds = listOf(role.id))))
        }
        assertEquals(HttpStatusCode.Created, userResponse.status, userResponse.bodyAsText())
        return userResponse.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private suspend fun createNomenclature(client: HttpClient, code: String): NomenclatureDto {
        val response = client.post("/api/wms/nomenclature") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateNomenclatureRequest(
                        code = code,
                        name = "Nomenclature $code",
                        type = NomenclatureType.RAW_MATERIAL,
                        unit = "pcs",
                        barcodes = listOf(CreateNomenclatureBarcodeRequest("BC-$code"))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureDto>>().data!!
    }

    private suspend fun createSubmittedDemand(client: HttpClient): DemandDetailDto {
        val material = createNomenclature(client, "APPR-MAT-${++nomenclatureCounter}")
        val createResponse = client.post("/api/demands") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateDemandRequest(
                        sourceType = DemandSourceType.MANUAL,
                        lines = listOf(DemandLineInput(1, material.id, "10", "kg"))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())
        val demand = createResponse.bodyAs<ApiResponse<DemandDetailDto>>().data!!
        val submitResponse = client.post("/api/demands/${demand.header.id}/submit") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SubmitDemandRequest(demand.header.version)))
        }
        assertEquals(HttpStatusCode.OK, submitResponse.status, submitResponse.bodyAsText())
        return submitResponse.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun createTemplate(
        client: HttpClient,
        code: String,
        stages: List<ApprovalStageInput>
    ): ApprovalTemplateDto {
        val response = createTemplateRaw(client, code, "DEMAND", stages)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ApprovalTemplateDto>>().data!!
    }

    private suspend fun createTemplateRaw(
        client: HttpClient,
        code: String,
        targetType: String,
        stages: List<ApprovalStageInput>
    ): HttpResponse {
        return client.post("/api/approvals/templates") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateApprovalTemplateRequest(code, "Template $code", targetType, stages)))
        }
    }

    private suspend fun startRoute(client: HttpClient, templateId: Long, targetId: Long): HttpResponse {
        return startRoute(client, StartApprovalRouteRequest(templateId, "DEMAND", targetId))
    }

    private suspend fun startRoute(client: HttpClient, request: StartApprovalRouteRequest): HttpResponse {
        return client.post("/api/approvals/routes") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
    }

    private suspend fun decide(
        client: HttpClient,
        routeId: Long,
        decision: ApprovalDecision,
        comment: String? = null
    ): HttpResponse {
        return client.post("/api/approvals/routes/$routeId/decisions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SubmitApprovalDecisionRequest(decision, comment)))
        }
    }

    private suspend fun cancelRoute(client: HttpClient, routeId: Long, reason: String): HttpResponse {
        return client.post("/api/approvals/routes/$routeId/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelApprovalRouteRequest(reason)))
        }
    }

    private suspend fun addAssignee(
        client: HttpClient,
        routeId: Long,
        stageId: Long,
        userId: Long
    ): HttpResponse {
        return client.post("/api/approvals/routes/$routeId/stages/$stageId/assignees") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddApprovalAssigneeRequest(ApprovalAssigneeType.USER, userId = userId)))
        }
    }

    private fun bumpDemandVersion(demandId: Long) {
        PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute("UPDATE demands SET version = version + 1 WHERE id = $demandId")
        }
    }

    private fun truncateApprovals() {
        PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute(
                """
                TRUNCATE TABLE
                  approval_decisions, approval_route_assignees, approval_route_stages,
                  approval_routes, approval_template_assignees, approval_template_stages,
                  approval_templates,
                  demand_procurements, demand_line_history, demand_history,
                  demand_lines, demands
                RESTART IDENTITY CASCADE
                """.trimIndent()
            )
        }
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            environment {
                config = HoconApplicationConfig(
                    ConfigFactory.parseString(
                        """
                        ktor {
                          deployment { port = 0 }
                          application {
                            modules = [org.example.project.ApplicationKt.module]
                          }
                        }
                        database {
                          url = "${TestDatabase.TEST_URL}"
                          user = "erp"
                          password = "erp"
                        }
                        session {
                          ttlMinutes = 60
                          cookieName = "erp_session"
                        }
                        security {
                          allowedOrigins = "http://localhost:8080,http://localhost:8081"
                          cookieSecure = false
                        }
                        storage {
                          path = "./.local/test-storage"
                        }
                        """.trimIndent()
                    )
                )
            }
            block()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(HttpCookies)
    }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T {
        return json.decodeFromString(serializer<T>(), bodyAsText())
    }

    private inline fun <reified T> stringify(value: T): String {
        return json.encodeToString(serializer<T>(), value)
    }

    companion object {
        private val TEST_URL = TestDatabase.TEST_URL
        private const val USER = "erp"
        private const val PASSWORD = "erp"

        private val json = Json {
            ignoreUnknownKeys = true
        }

        private var nomenclatureCounter = 0

        @JvmStatic
        @BeforeClass
        fun setup() {
            TestDatabase.init()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            TestDatabase.destroy()
        }
    }
}
