package org.example.project.service

import org.example.project.dto.*
import org.example.project.generated.jooq.tables.records.*
import org.example.project.repository.ProjectRepository

fun UsersRecord.toDto(): UserDto = UserDto(
    id = this.id,
    login = this.login,
    fullName = this.fullName,
    email = this.email,
    departmentId = this.departmentId,
    active = this.active ?: true,
    createdAt = this.createdAt?.toString(),
    version = this.version ?: 0
)

fun DepartmentsRecord.toDto(): DepartmentDto = DepartmentDto(
    id = this.id,
    code = this.code,
    name = this.name,
    parentId = this.parentId,
    headId = this.headId
)

fun RolesRecord.toDto(): RoleDto = RoleDto(
    id = this.id,
    code = this.code,
    name = this.name,
    description = this.description
)

fun PermissionsRecord.toDto(): PermissionDto = PermissionDto(
    id = this.id,
    roleId = this.roleId,
    resource = this.resource,
    action = this.action,
    scope = this.scope
)

fun ProjectsRecord.toDto(members: List<ProjectMemberDto> = emptyList()): ProjectDto = ProjectDto(
    id = this.id,
    number = this.number,
    name = this.name,
    fullName = this.fullName,
    description = this.description,
    projectType = this.projectType?.let { ProjectType.valueOf(it) },
    customer = this.customer,
    ownerId = this.ownerId,
    departmentId = this.departmentId,
    plannedStartAt = this.plannedStartAt?.toString(),
    plannedEndAt = this.plannedEndAt?.toString(),
    priority = this.priority?.let { ProjectPriority.valueOf(it) } ?: ProjectPriority.NORMAL,
    status = this.status?.let { ProjectStatus.valueOf(it) } ?: ProjectStatus.DRAFT,
    openingReason = this.openingReason,
    openedBy = this.openedBy,
    openedAt = this.openedAt?.toString(),
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    members = members
)

fun ProjectRepository.MemberRow.toDto(): ProjectMemberDto = ProjectMemberDto(
    userId = this.userId,
    fullName = this.fullName,
    addedAt = this.addedAt?.toString(),
    addedBy = this.addedBy
)
