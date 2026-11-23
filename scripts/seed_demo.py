"""
Демо-данные ERP «Авиаагрегат-Н» для локального просмотра интерфейса.

Работает только через HTTP API (как веб-клиент) на ПУСТОЙ базе: создаёт первого
администратора, сотрудников с ролями и данные по всем разделам. Не запускать на
рабочей базе.

    python scripts/seed_demo.py [http://localhost:8080]

Учётные записи — только для локальной демо-базы; пароль задаётся переменной
ERP_DEMO_PASSWORD (иначе используется DEFAULT_PASSWORD ниже).
"""
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
DEFAULT_PASSWORD = "Demo-ERP-2026!"
PASSWORD = os.environ.get("ERP_DEMO_PASSWORD", DEFAULT_PASSWORD)
TODAY = date.today()


def d(days: int) -> str:
    return (TODAY + timedelta(days=days)).isoformat()


def ts(days: int) -> str:
    return d(days) + "T00:00:00Z"


def key(prefix: str = "seed") -> str:
    return f"{prefix}-{uuid.uuid4()}"


class Client:
    def __init__(self, name: str):
        self.name = name
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method: str, path: str, body=None, expect=(200, 201), raw: bytes = None, headers=None):
        data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
        req = urllib.request.Request(BASE + path, data=data, method=method)
        if raw is None and body is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with self.opener.open(req) as resp:
                status, text = resp.status, resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode("utf-8")
        if status not in expect:
            raise RuntimeError(f"[{self.name}] {method} {path} -> {status}: {text[:400]}")
        payload = json.loads(text) if text and text.startswith("{") else {}
        return payload.get("data", payload)

    def get(self, path):
        return self.call("GET", path)

    def post(self, path, body=None, expect=(200, 201)):
        return self.call("POST", path, body if body is not None else {}, expect)

    def put(self, path, body):
        return self.call("PUT", path, body)

    def login(self, login: str):
        self.post("/api/auth/login", {"login": login, "password": PASSWORD})
        return self

    def upload(self, path: str, file_name: str, content: bytes, mime: str = "application/pdf"):
        boundary = "----seed" + uuid.uuid4().hex
        body = (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
            f"Content-Type: {mime}\r\n\r\n"
        ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
        return self.call("POST", path, raw=body, headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})


def main():
    admin = Client("admin")
    status = admin.get("/api/admin/bootstrap-status")
    if not status.get("required"):
        sys.exit("В базе уже есть пользователи — демо-данные заливаются только в пустую базу.")
    admin.post("/api/admin/bootstrap", {"login": "demo.admin", "password": PASSWORD, "fullName": "Смирнова Анна Викторовна"})
    admin.login("demo.admin")
    me = admin.get("/api/auth/me")["user"]

    # --- Оргструктура и роли ---
    deps = {code: admin.post("/api/departments", {"code": code, "name": name})["id"] for code, name in [
        ("OGK", "Отдел главного конструктора"), ("OMTS", "Отдел снабжения"), ("SKLAD", "Склад"),
        ("PROD", "Сборочный цех"), ("OTK", "ОТК"), ("PDO", "Планово-диспетчерский отдел"),
    ]}

    def role(code, name, perms):
        return admin.post("/api/roles", {"code": code, "name": name, "permissions": [
            {"resource": p.split(":")[0], "action": p.split(":")[1]} for p in perms]})["id"]

    r_pm = role("PM", "Руководитель проекта", ["projects:create", "projects:read_any", "projects:manage_members", "projects:manage_plan",
                                               "tasks:create", "tasks:assign_any", "tasks:read_any", "documents:create", "documents:read",
                                               "demands:read_any", "bom:read_any", "production:read"])
    r_eng = role("ENGINEER", "Конструктор", ["bom:read_any", "bom:create", "bom:update", "bom:approve", "bom:explode",
                                             "demands:create", "demands:read_any", "demands:update", "suppliers:read", "suppliers:select", "wms:read",
                                             "documents:create", "documents:read", "projects:read"])
    r_buyer = role("BUYER", "Снабженец", ["suppliers:read", "suppliers:create", "suppliers:update", "suppliers:offer", "suppliers:order",
                                          "suppliers:receive", "demands:read_any", "wms:read", "users:read_any", "invoices:manage",
                                          "documents:create", "documents:read"])
    r_chief = role("SUPPLY_CHIEF", "Начальник снабжения", ["suppliers:read", "suppliers:read_any", "suppliers:approve_order", "suppliers:manage",
                                                           "suppliers:verify", "demands:read_any", "demands:approve", "wms:read", "invoices:read"])
    r_keeper = role("KEEPER", "Кладовщик", ["wms:read", "wms:post", "wms:manage", "wms:issue_without_reservation", "wms:inspect_returns"])
    r_master = role("MASTER", "Мастер цеха", ["production:read", "production:manage", "wms:read", "bom:read_any", "projects:read"])

    def user(login, full_name, dep, roles):
        return admin.post("/api/users", {"login": login, "password": PASSWORD, "fullName": full_name,
                                         "departmentId": deps[dep], "roleIds": roles})

    u = {
        "pm": user("volkov", "Волков Сергей Петрович", "PDO", [r_pm]),
        "eng": user("lebedev", "Лебедев Игорь Андреевич", "OGK", [r_eng]),
        "eng2": user("kuznetsova", "Кузнецова Ольга Сергеевна", "OGK", [r_eng]),
        "buyer": user("sidorova", "Сидорова Мария Ивановна", "OMTS", [r_buyer]),
        "chief": user("orlov", "Орлов Дмитрий Николаевич", "OMTS", [r_chief]),
        "keeper": user("ivanov", "Иванов Павел Олегович", "SKLAD", [r_keeper]),
        "master": user("belov", "Белов Андрей Викторович", "PROD", [r_master]),
        "qa": user("zaitseva", "Зайцева Елена Юрьевна", "OTK", []),
    }
    c = {k: Client(k).login(v["login"]) for k, v in u.items()}

    # --- Склад ---
    wh = admin.post("/api/wms/warehouses", {"code": "WH-01", "name": "Центральный склад", "address": "Новочеркасск, ул. 26 Бакинских Комиссаров, 11В"})
    wh2 = admin.post("/api/wms/warehouses", {"code": "WH-02", "name": "Склад сборочного цеха"})
    cells = {}
    for code, name, purpose in [("A-01-01", "Стеллаж A, полка 1", "STORAGE"), ("A-01-02", "Стеллаж A, полка 2", "STORAGE"),
                                ("B-02-01", "Стеллаж B, крепёж", "STORAGE"), ("E-01", "Электротехника", "STORAGE"),
                                ("Q-01", "Карантин", "QUARANTINE"), ("R-01", "Зона приёмки", "RECEIVING")]:
        try:
            cells[code] = admin.post("/api/wms/locations", {"warehouseId": wh["id"], "code": code, "name": name, "kind": "CELL", "purpose": purpose})["id"]
        except RuntimeError:
            cells[code] = admin.post("/api/wms/locations", {"warehouseId": wh["id"], "code": code, "name": name, "kind": "CELL", "purpose": "STORAGE"})["id"]
    cell2 = admin.post("/api/wms/locations", {"warehouseId": wh2["id"], "code": "C-01", "name": "Цеховая кладовая", "kind": "CELL"})["id"]

    nomen = {}
    for code, name, typ, unit in [
        ("NSH-10", "Насос шестерённый НШ-10", "FINISHED_PRODUCT", "шт"),
        ("HSG-10", "Корпус насоса НШ-10", "PURCHASED_COMPONENT", "шт"),
        ("GEAR-10", "Шестерня ведущая z=12", "PURCHASED_COMPONENT", "шт"),
        ("SEAL-22", "Манжета 22х32", "PURCHASED_COMPONENT", "шт"),
        ("BOLT-M6x20", "Болт М6х20 оцинк.", "STANDARD_PRODUCT", "шт"),
        ("NUT-M6", "Гайка М6 оцинк.", "STANDARD_PRODUCT", "шт"),
        ("WIRE-MGTF", "Провод МГТФ 0,35", "MATERIAL", "м"),
        ("TOP-120/5", "Трансформатор ТОП-0,66-5-0,2S-120/5 У3", "PURCHASED_COMPONENT", "шт"),
        ("TOP-30/5", "Трансформатор ТОП-0,66-5-0,2S-30/5 У3", "PURCHASED_COMPONENT", "шт"),
        ("AL-SHEET", "Лист Д16АТ 2,0 мм", "RAW_MATERIAL", "кг"),
    ]:
        nomen[code] = admin.post("/api/wms/nomenclature", {"code": code, "name": name, "type": typ, "unit": unit})["id"]

    def initial_stock(nom, qty, cell):
        r = c["keeper"].post("/api/wms/receipts", {"sourceType": "INITIAL_STOCK", "warehouseId": wh["id"],
                                                     "lines": [{"lineNo": 1, "nomenclatureId": nomen[nom], "expectedQuantity": qty, "locationId": cells[cell]}]})
        c["keeper"].post(f"/api/wms/receipts/{r['id']}/post", {"version": r.get("version", 0)})

    for nom, qty, cell in [("BOLT-M6x20", "1200", "B-02-01"), ("NUT-M6", "800", "B-02-01"), ("WIRE-MGTF", "450", "E-01"),
                           ("SEAL-22", "14", "A-01-02"), ("GEAR-10", "6", "A-01-01"), ("AL-SHEET", "320", "A-01-01")]:
        initial_stock(nom, qty, cell)
    c["keeper"].post("/api/wms/reservations", {"reasonType": "OTHER", "reasonNote": "Сборка опытной партии НШ-10",
                                                "nomenclatureId": nomen["SEAL-22"], "warehouseId": wh["id"],
                                                "locationId": cells["A-01-02"], "quantity": "6"})

    # --- Проекты и задачи ---
    projects = []
    for name, customer, start, end in [
        ("Модернизация агрегата АН-24", "АО «Авиаремонт»", -60, 180),
        ("Серия насосов НШ-10", "ПАО «Гидромаш»", -20, 120),
        ("Стенд испытаний трансформаторов", "Внутренний проект", -5, 60),
    ]:
        p = c["pm"].post("/api/projects", {"name": name, "customer": customer, "projectType": "ORDER_DEVELOPMENT",
                                           "ownerId": u["pm"]["id"], "plannedStartAt": ts(start), "plannedEndAt": ts(end),
                                           "description": f"Проект для заказчика {customer}"})
        for m in ("eng", "eng2", "buyer", "master", "qa"):
            c["pm"].post(f"/api/projects/{p['id']}/members", {"userId": u[m]["id"]})
        projects.append(p)

    def task(project, title, assignee, reviewer=None, due=14, desc=None):
        body = {"title": title, "description": desc or title, "area": "PROJECT", "projectId": project["id"],
                "type": "REVIEWED" if reviewer else "GENERAL", "assigneeId": u[assignee]["id"], "dueDate": ts(due)}
        if reviewer:
            body.update({"reviewerId": u[reviewer]["id"], "acceptanceCriteria": "Результат согласован с ОТК"})
        return c["pm"].post("/api/tasks", body)

    t1 = task(projects[0], "Выпустить КД на кронштейн крепления", "eng", "qa", 10, "Комплект КД по ЕСКД: чертёж детали и спецификация")
    t2 = task(projects[0], "Подобрать поставщика манжет 22х32", "buyer", None, 5)
    t3 = task(projects[1], "Сборка опытной партии НШ-10 (5 шт)", "master", "qa", 21)
    t4 = task(projects[1], "Расчёт норм расхода материалов", "eng2", None, -3, "Просроченная задача для проверки подсветки сроков")
    t5 = task(projects[2], "Разработать методику испытаний", "eng", "qa", 30)
    task(projects[2], "Закупить трансформаторы ТОП-0,66", "buyer", None, 7)

    def act(client, t, verb, extra=None):
        body = {"version": t.get("version", 0)}
        body.update(extra or {})
        return client.post(f"/api/tasks/{t['id']}/{verb}", body)

    t1 = act(c["eng"], t1, "accept"); t1 = act(c["eng"], t1, "start")
    for text in ["Прошу уточнить материал кронштейна: Д16АТ или АМг6?", "Д16АТ, толщина 2 мм — как в исходной КД.", "Принял, чертёж будет к пятнице."]:
        who = c["pm"] if text.startswith("Д16") else c["eng"]
        who.post(f"/api/tasks/{t1['id']}/chat/messages", {"content": text, "idempotencyKey": key("msg")})
    t3 = act(c["master"], t3, "accept"); t3 = act(c["master"], t3, "start")
    t3 = act(c["master"], t3, "result", {"result": "Собрано 5 шт, протоколы приложены"}); t3 = act(c["master"], t3, "send-to-review")
    t2 = act(c["buyer"], t2, "accept")

    # --- Чат ---
    conv = c["pm"].post("/api/conversations", {"kind": "GROUP", "title": "Планёрка по НШ-10",
                                                "memberIds": [u[m]["id"] for m in ("eng", "buyer", "master", "qa")]})
    for who, text in [("pm", "Коллеги, по НШ-10 — сроки сборки сдвигаются из-за манжет."), ("buyer", "Счёт от поставщика будет завтра, срок поставки 5 дней."),
                      ("master", "Корпуса и шестерни на складе есть, начнём подготовку."), ("qa", "Протокол испытаний обновлю после первой сборки.")]:
        c[who].post(f"/api/conversations/{conv['id']}/messages", {"content": text, "idempotencyKey": key("conv")})

    # --- BOM и производство ---
    model = c["eng"].post("/api/bom/models", {"code": "NSH-10", "name": "Насос шестерённый НШ-10", "description": "Шестерённый насос 10 см³"})
    rev = c["eng"].post(f"/api/bom/models/{model['id']}/revisions", {"revisionNumber": "A", "changeReason": "Первичный выпуск"})
    for child, qty in [("HSG-10", "1"), ("GEAR-10", "2"), ("SEAL-22", "2"), ("BOLT-M6x20", "8"), ("NUT-M6", "8")]:
        c["eng"].post(f"/api/bom/revisions/{rev['id']}/lines", {"parentNomenclatureId": nomen["NSH-10"], "childNomenclatureId": nomen[child], "quantity": qty, "unit": "шт"})
    rev = c["eng"].get(f"/api/bom/revisions/{rev['id']}")
    rev = c["eng"].post(f"/api/bom/revisions/{rev['id']}/approve", {"version": rev.get("version", 0)})
    c["eng"].post(f"/api/bom/revisions/{rev['id']}/activate", {"version": rev.get("version", 0)})
    rev_b = c["eng"].post(f"/api/bom/models/{model['id']}/revisions", {"revisionNumber": "B", "changeReason": "Замена манжеты на усиленную"})

    order = c["master"].post("/api/production/orders", {"projectId": projects[1]["id"], "productModelId": model["id"], "bomRevisionId": rev["id"],
                                                        "plannedQuantity": 5, "responsibleId": u["master"]["id"],
                                                        "plannedStartAt": ts(-2), "plannedEndAt": ts(18),
                                                        "stages": [{"stageType": "KITTING", "name": "Комплектация", "sequence": 1},
                                                                   {"stageType": "ASSEMBLY", "name": "Сборка", "sequence": 2},
                                                                   {"stageType": "TESTING", "name": "Испытания", "sequence": 3}]})
    order = c["master"].post(f"/api/production/orders/{order['id']}/plan", {"version": order.get("version", 0)})
    c["master"].post(f"/api/production/orders/{order['id']}/start", {"version": order.get("version", 0)})
    c["master"].post(f"/api/production/orders/{order['id']}/materials/init-from-bom")

    # --- Снабжение → поставщик → заказ → счёт ---
    demand = c["eng"].post("/api/demands", {"sourceType": "MANUAL", "requiredDate": d(30), "priority": "HIGH",
                                            "lines": [{"lineNo": 1, "nomenclatureId": nomen["TOP-120/5"], "quantity": "3", "unit": "шт"},
                                                      {"lineNo": 2, "nomenclatureId": nomen["TOP-30/5"], "quantity": "3", "unit": "шт"}]})
    demand = c["eng"].post(f"/api/demands/{demand['header']['id']}/submit", {"version": demand["header"].get("version", 0)})
    demand = c["chief"].post(f"/api/demands/{demand['header']['id']}/approve", {"version": demand["header"].get("version", 0)})
    c["eng"].post("/api/demands/from-bom", {"projectId": projects[1]["id"], "revisionId": rev["id"], "releaseQuantity": "5", "requiredDate": d(20), "idempotencyKey": key("bom")})

    sup = c["buyer"].post("/api/suppliers", {"name": "ОАО «СЗТТ»", "inn": "6658017928", "idempotencyKey": key("sup")})
    c["chief"].post(f"/api/suppliers/{sup['supplier']['id']}/verification",
                    {"status": "VERIFIED", "note": "Проверен СБ", "version": sup["supplier"].get("version", 0), "idempotencyKey": key("ver")})
    c["buyer"].post("/api/suppliers", {"name": "ООО «КрепёжСнаб»", "inn": "7701555123", "idempotencyKey": key("sup")})
    offer = c["buyer"].post("/api/supplier-offers", {
        "demandId": demand["header"]["id"], "supplierId": sup["supplier"]["id"], "compliance": "FULL", "documentsComplete": True,
        "deliveryDays": 25, "paymentTerms": "100% предоплата", "validUntil": d(14),
        "lines": [{"demandLineId": demand["lines"][0]["id"], "quantity": "3", "unitPrice": "2220.00", "vatRate": "20"},
                  {"demandLineId": demand["lines"][1]["id"], "quantity": "3", "unitPrice": "2270.00", "vatRate": "20"}],
        "idempotencyKey": key("offer")})
    offer = c["buyer"].get(f"/api/supplier-offers/{offer['id']}")
    c["eng"].post(f"/api/supplier-offers/{offer['id']}/select", {"reason": "Производитель, лучший срок", "version": offer.get("version", 0), "idempotencyKey": key("sel")})
    po = c["buyer"].post("/api/purchase-orders", {"offerId": offer["id"], "expectedAt": d(30), "paymentTerms": "100% предоплата",
                                                  "deliveryAddress": "Склад WH-01", "idempotencyKey": key("po"),
                                                  "lines": [{"offerLineId": l["id"], "quantity": "3"} for l in offer["lines"]]})
    po = c["buyer"].post(f"/api/purchase-orders/{po['id']}/submit", {"version": po.get("version", 0), "idempotencyKey": key()})
    po = c["chief"].post(f"/api/purchase-orders/{po['id']}/approve", {"version": po.get("version", 0), "idempotencyKey": key()})
    po = c["buyer"].post(f"/api/purchase-orders/{po['id']}/send", {"version": po.get("version", 0), "idempotencyKey": key()})

    doc = c["buyer"].post("/api/documents", {"name": "Счёт № 219658 ОАО «СЗТТ»", "docType": "INVOICE"})
    c["buyer"].upload(f"/api/documents/{doc['id']}/versions", "invoice-219658.pdf", b"%PDF-1.4 demo invoice 219658")
    lines = []
    for i, line in enumerate(po["lines"], start=1):
        net = round(float(line["quantity"]) * float(line["unitPrice"]), 2)
        lines.append({"lineNo": i, "supplierItemCode": f"1000018598{i}", "supplierItemName": line.get("nomenclatureName") or "Трансформатор",
                      "nomenclatureId": line["nomenclatureId"], "purchaseOrderLineId": line["id"], "quantity": "3", "unit": "шт",
                      "unitPrice": line["unitPrice"], "vatRate": "20", "vatAmount": f"{net * 0.2:.2f}", "lineTotal": f"{net:.2f}"})
    net_total = sum(float(l["lineTotal"]) for l in lines)
    invoice = c["buyer"].post("/api/supplier-invoices", {
        "number": "219658", "invoiceDate": d(-1), "supplierId": sup["supplier"]["id"], "purchaseOrderId": po["id"], "documentId": doc["id"],
        "supplierInn": "6658017928", "supplierKpp": "665801001", "netTotal": f"{net_total:.2f}", "vatTotal": f"{net_total * 0.2:.2f}",
        "grossTotal": f"{net_total * 1.2:.2f}", "validUntil": d(8), "paymentTerms": "100% предоплата", "prepaymentPct": "100",
        "leadTimeDays": 25, "leadTimeKind": "WORKING", "deliveryTerms": "До склада ТК «Деловые Линии»",
        "paymentPurpose": "Оплата по заказу клиента №219658", "supplierManager": "Урьева Р. В., +7 (343) 234-31-02", "lines": lines})
    invoice = c["buyer"].post(f"/api/supplier-invoices/{invoice['id']}/confirm-check", {"version": invoice.get("version", 0)})
    c["buyer"].post(f"/api/supplier-invoices/{invoice['id']}/receipt", {"warehouseId": wh["id"], "version": invoice.get("version", 0)})

    # --- Документы ---
    for name, typ in [("Технические условия НШ-10", "TECHNICAL_SPECIFICATION"), ("Договор поставки № 15/2026", "CONTRACT"),
                      ("Протокол испытаний НШ-10 №1", "TEST_PROTOCOL")]:
        dd = c["pm"].post("/api/documents", {"name": name, "docType": typ, "description": name})
        c["pm"].upload(f"/api/documents/{dd['id']}/versions", f"{typ.lower()}.pdf", f"%PDF-1.4 {name}".encode("utf-8"))

    print("Демо-данные созданы. Пользователи: demo.admin, volkov, lebedev, kuznetsova, sidorova, orlov, ivanov, belov, zaitseva")


if __name__ == "__main__":
    main()
