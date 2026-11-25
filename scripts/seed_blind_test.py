"""
Скрипт тестовых данных для слепого тестирования 4 ролей (D-65, SUP-UX-002).
Работает через HTTP API на пустой базе ERP «Авиаагрегат-Н»:
  python scripts/seed_blind_test.py [http://localhost:8080]

Создаёт:
- Администратора demo.admin
- Подразделения (OGK, OMTS, SKLAD, PROD, OTK, PDO)
- Пользователей и роли:
  - engineer (Лебедев Игорь Андреевич, Ведущий инженер)
  - buyer (Сидорова Мария Ивановна, Снабженец)
  - keeper (Иванов Павел Олегович, Кладовщик)
  - operator (Белов Андрей Викторович, Оператор сборочного цеха)
  - pm (Волков Сергей Петрович, РП)
  - qa (Зайцева Елена Юрьевна, ОТК)
- Склад WH-01 с ячейками A-01-01, A-01-02, Q-01
- Номенклатуру: DRV-24V (Привод линейный 24В, шт), BOLT-M6x20 (Болт М6х20 оцинк., шт), CBL-24V (Жгут питания 24В, компл)
- Начальный приход на WH-01 (INITIAL_STOCK, сразу post):
  - DRV-24V: ровно 4 шт в A-01-01
  - BOLT-M6x20: 200 шт в A-01-02
  - CBL-24V: 5 компл в A-01-02
- Поставщиков (ООО «ТехноПривод», АО «ЭлектроМеханика») со статусом VERIFIED
- Проект «Стенд ресурсных испытаний СРИ-10» (заказчик АО «Авиаремонт») с участниками
- Рабочую задачу для operator на проект
- Вывод сводки в stdout (JSON)
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
DEFAULT_PASSWORD = "BlindTest-2026!"
PASSWORD = os.environ.get("ERP_DEMO_PASSWORD", DEFAULT_PASSWORD)
TODAY = date.today()


def d(days: int) -> str:
    return (TODAY + timedelta(days=days)).isoformat()


def ts(days: int) -> str:
    return d(days) + "T00:00:00Z"


def key(prefix: str = "blind") -> str:
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

    def login(self, login: str):
        self.post("/api/auth/login", {"login": login, "password": PASSWORD})
        return self


def main():
    admin = Client("admin")
    status = admin.get("/api/admin/bootstrap-status")
    if not status.get("required"):
        sys.exit("База уже инициализирована. Для слепого тестирования требуется пустая база.")
    
    admin.post("/api/admin/bootstrap", {"login": "demo.admin", "password": PASSWORD, "fullName": "Смирнова Анна Викторовна"})
    admin.login("demo.admin")

    # --- Подразделения ---
    deps = {code: admin.post("/api/departments", {"code": code, "name": name})["id"] for code, name in [
        ("OGK", "Отдел главного конструктора"),
        ("OMTS", "Отдел снабжения"),
        ("SKLAD", "Центральный склад"),
        ("PROD", "Сборочный цех"),
        ("OTK", "ОТК"),
        ("PDO", "Планово-диспетчерский отдел"),
    ]}

    def role(code, name, perms):
        return admin.post("/api/roles", {"code": code, "name": name, "permissions": [
            {"resource": p.split(":")[0], "action": p.split(":")[1]} for p in perms]})["id"]

    r_eng = role("ENGINEER", "Ведущий инженер", [
        "projects:create", "projects:read", "projects:read_any", "projects:manage_members",
        "bom:read_any", "bom:create", "bom:update", "bom:approve", "bom:explode",
        "demands:create", "demands:read_any", "demands:update", "suppliers:read", "suppliers:select",
        "wms:read", "documents:create", "documents:read", "tasks:create"
    ])
    r_buyer = role("BUYER", "Снабженец", [
        "suppliers:read", "suppliers:create", "suppliers:update", "suppliers:offer", "suppliers:order",
        "suppliers:receive", "suppliers:verify", "demands:create", "demands:read_any", "wms:read",
        "users:read_any", "invoices:manage", "documents:create", "documents:read", "projects:read_any"
    ])
    r_keeper = role("KEEPER", "Кладовщик", [
        "wms:read", "wms:post", "wms:manage", "wms:issue_without_reservation", "wms:inspect_returns",
        "suppliers:read", "projects:read_any"
    ])
    r_operator = role("OPERATOR", "Оператор сборочного цеха", [
        "production:read", "wms:read", "bom:read_any"
    ])
    r_pm = role("PM", "Руководитель проекта", [
        "projects:create", "projects:read_any", "projects:manage_members",
        "tasks:create", "tasks:assign_any", "tasks:read_any"
    ])
    r_qa = role("QA", "Инженер ОТК", [
        "production:read", "tasks:create", "tasks:read_any"
    ])

    def user(login, full_name, dep, roles):
        return admin.post("/api/users", {"login": login, "password": PASSWORD, "fullName": full_name,
                                         "departmentId": deps[dep], "roleIds": roles})

    u = {
        "engineer": user("engineer", "Лебедев Игорь Андреевич", "OGK", [r_eng]),
        "buyer": user("buyer", "Сидорова Мария Ивановна", "OMTS", [r_buyer]),
        "keeper": user("keeper", "Иванов Павел Олегович", "SKLAD", [r_keeper]),
        "operator": user("operator", "Белов Андрей Викторович", "PROD", [r_operator]),
        "pm": user("pm", "Волков Сергей Петрович", "PDO", [r_pm]),
        "qa": user("qa", "Зайцева Елена Юрьевна", "OTK", [r_qa]),
    }
    clients = {k: Client(k).login(v["login"]) for k, v in u.items()}

    # --- Склад и ячейки ---
    wh = admin.post("/api/wms/warehouses", {"code": "WH-01", "name": "Центральный склад", "address": "г. Ростов-на-Дону, ул. Промышленная, 10"})
    cells = {}
    for code, name, purpose in [
        ("A-01-01", "Стеллаж A, полка 1", "STORAGE"),
        ("A-01-02", "Стеллаж A, полка 2", "STORAGE"),
        ("Q-01", "Зона карантина", "QUARANTINE"),
    ]:
        cells[code] = admin.post("/api/wms/locations", {"warehouseId": wh["id"], "code": code, "name": name, "kind": "CELL", "purpose": purpose})["id"]

    # --- Номенклатура ---
    nomen = {}
    for code, name, typ, unit in [
        ("DRV-24V", "Привод линейный 24В", "PURCHASED_COMPONENT", "шт"),
        ("BOLT-M6x20", "Болт М6х20 оцинк.", "STANDARD_PRODUCT", "шт"),
        ("CBL-24V", "Жгут питания 24В", "MATERIAL", "компл"),
    ]:
        nomen[code] = admin.post("/api/wms/nomenclature", {"code": code, "name": name, "type": typ, "unit": unit})["id"]

    # --- Начальный приход (INITIAL_STOCK) ровно 4 шт DRV-24V и др. ---
    def initial_stock(nom_code, qty_str, cell_code):
        r = clients["keeper"].post("/api/wms/receipts", {
            "sourceType": "INITIAL_STOCK",
            "warehouseId": wh["id"],
            "lines": [{"lineNo": 1, "nomenclatureId": nomen[nom_code], "expectedQuantity": qty_str, "locationId": cells[cell_code]}]
        })
        clients["keeper"].post(f"/api/wms/receipts/{r['id']}/post", {"version": r.get("version", 0)})

    initial_stock("DRV-24V", "4", "A-01-01")
    initial_stock("BOLT-M6x20", "200", "A-01-02")
    initial_stock("CBL-24V", "5", "A-01-02")

    # --- Поставщики и верификация ---
    suppliers = {}
    for name, inn in [
        ("ООО «ТехноПривод»", "7701234560"),
        ("АО «ЭлектроМеханика»", "6658017928"),
    ]:
        sup = clients["buyer"].post("/api/suppliers", {"name": name, "inn": inn, "idempotencyKey": key("sup")})
        clients["buyer"].post(f"/api/suppliers/{sup['id']}/verify", {"status": "VERIFIED", "version": sup.get("version", 0)})
        suppliers[name] = sup["id"]

    # --- Проект ---
    proj = clients["engineer"].post("/api/projects", {
        "name": "Стенд ресурсных испытаний СРИ-10",
        "customer": "АО «Авиаремонт»",
        "projectType": "ORDER_DEVELOPMENT",
        "ownerId": u["engineer"]["id"],
        "plannedStartAt": ts(0),
        "plannedEndAt": ts(90),
        "description": "Разработка и сборка стенда ресурсных испытаний СРИ-10"
    })
    for m_key in ("buyer", "keeper", "operator", "qa", "pm"):
        clients["engineer"].post(f"/api/projects/{proj['id']}/members", {"userId": u[m_key]["id"]})

    # --- Рабочая задача для оператора ---
    task = clients["pm"].post("/api/tasks", {
        "title": "Подготовить и промаркировать жгуты питания 24В для стенда СРИ-10",
        "description": "Собрать, прозвонить и промаркировать жгуты питания 24В согласно технической спецификации",
        "area": "PROJECT",
        "projectId": proj["id"],
        "type": "REVIEWED",
        "assigneeId": u["operator"]["id"],
        "reviewerId": u["qa"]["id"],
        "dueDate": ts(14),
        "acceptanceCriteria": "Жгуты собраны в количестве 2 компл., прозвонены и промаркированы по схеме Э3"
    })

    summary = {
        "base": BASE,
        "password": PASSWORD,
        "admin": "demo.admin",
        "users": {k: v["login"] for k, v in u.items()},
        "projectId": proj["id"],
        "projectName": proj["name"],
        "warehouseId": wh["id"],
        "nomenclature": nomen,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
