"""Build and verify the exact OpenAPI document packaged by processResources.

Install tools/requirements-api-docs.txt into build/doc-validation (see README).
No database or network is used during validation.
"""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/doc-validation"))
OUTPUT = ROOT / "docs/openapi.json"
METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}


def assemble():
    document = {
        "openapi": "3.1.0",
        "info": {
            "title": "Roy Backend API",
            "version": "1.0.0",
            "description": (
                "API сервиса «Рой»: выберите тематический блок, чтобы посмотреть запросы, ответы и примеры. "
                "Для защищённых операций нажмите Authorize и вставьте токен доступа без префикса Bearer. "
                "[Контракты WebSocket](/docs/websocket) описаны отдельно."
            ),
        },
        "servers": [{"url": "/", "description": "Тот же origin; при развёртывании адрес заменяется значением PUBLIC_BASE_URL"}],
        "tags": [
            {"name": "Авторизация", "description": "Регистрация, вход, обновление токенов и выход."},
            {"name": "Профиль", "description": "Текущий пользователь, имя, пароль и удаление аккаунта."},
            {"name": "Уведомления", "description": "Центр уведомлений, прочтение, настройки каналов, геопозиция и push-устройства."},
            {"name": "Карта и точки", "description": "Публичная карта, фильтры, карточка и создание точки, квота, обратное геокодирование."},
            {"name": "Темы и членство", "description": "Каталог и добавление тем, вступление и выход из чата точки."},
            {"name": "Чаты", "description": "Мои чаты, состояние чата и курсор прочтения."},
            {"name": "Сообщения", "description": "История, поиск и отправка сообщений, ограничения отправки и частоты."},
            {"name": "Встречи и бронирования", "description": "Предложения встреч, голоса, бронирование и закрытие."},
            {"name": "Жалобы", "description": "Жалоба на участника в контексте чата."},
            {"name": "Правовая информация", "description": "Версия и документ политики конфиденциальности."},
            {"name": "Служебные", "description": "Корневой маршрут и готовность сервера."},
        ],
        "paths": {},
        "components": {
            "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}},
            "schemas": {
                "Error": {
                    "type": "object", "required": ["error", "requestId"], "additionalProperties": False,
                    "properties": {
                        "requestId": {"type": "string", "format": "uuid"},
                        "error": {
                            "type": "object", "required": ["code", "message", "details", "retryAfterSeconds"],
                            "additionalProperties": False,
                            "properties": {
                                "code": {"type": "string"}, "message": {"type": "string"},
                                "details": {"type": ["object", "null"], "additionalProperties": True},
                                "retryAfterSeconds": {"type": ["integer", "null"], "minimum": 0},
                            },
                        },
                    },
                },
            },
        },
    }
    for path in sorted((ROOT / "docs/openapi").glob("*.json")):
        fragment = json.loads(path.read_text(encoding="utf-8-sig"))
        for name, item in fragment["paths"].items():
            target = document["paths"].setdefault(name, {})
            assert not target.keys() & item.keys(), f"Duplicate operation: {name}"
            target.update(item)
        for name, schema in fragment["components"]["schemas"].items():
            assert name not in document["components"]["schemas"], f"Duplicate schema {name}"
            document["components"]["schemas"][name] = schema
    for item in document["paths"].values():
        for method, operation in item.items():
            if method not in METHODS:
                continue
            correlation_parameters = [{
                "name": "X-Request-Id", "in": "header", "required": False,
                "description": "Необязательный идентификатор запроса. Канонический UUID отражается в заголовке ответа; отсутствующее или некорректное значение заменяется UUID, созданным сервером.",
                "schema": {"type": "string"}, "example": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            }]
            operation["parameters"] = operation.get("parameters", []) + [
                parameter for parameter in correlation_parameters
                if not any(existing.get("in") == "header" and existing.get("name", "").lower() == "x-request-id"
                           for existing in operation.get("parameters", []))
            ]
            operation["responses"]["406"] = {
                "description": (
                    "Невозможно согласовать формат ответа: Accept исключает application/json, а обработчик "
                    "пытается вернуть JSON успешного результата или ошибки, включая ошибку авторизации. "
                    "Ktor возвращает пустое тело без error.code и JSON ошибки. "
                    "Успешные текстовые/Markdown-ответы и ответы 204 не требуют согласования JSON. "
                    "Передавайте `Accept: application/json` или `Accept: */*`.\n\n"
                    "Пример HTTP-ответа:\n```http\nHTTP/1.1 406 Not Acceptable\n\n```\nТело ответа отсутствует."
                ),
            }
    return document


def validate(document):
    from openapi_spec_validator import validate as validate_openapi
    from jsonschema import Draft202012Validator, FormatChecker

    validate_openapi(document)
    count = 0

    def example(schema, value, location):
        nonlocal count
        rooted = dict(schema, components=document["components"])
        try:
            Draft202012Validator(rooted, format_checker=FormatChecker()).validate(value)
        except Exception as error:
            raise AssertionError(f"Invalid example {location}: {error}") from error
        count += 1

    def media_examples(media, location):
        assert "schema" in media, f"Missing schema: {location}"
        assert "examples" in media or "example" in media, f"Missing examples: {location}"
        if "example" in media:
            example(media["schema"], media["example"], location)
        for name, item in media.get("examples", {}).items():
            assert "value" in item, f"Examples must be local: {location}/{name}"
            example(media["schema"], item["value"], f"{location}/{name}")

    actual = set()
    for path in (ROOT / "src/main/kotlin/api").glob("*.kt"):
        if path.name == "DocumentationRoutes.kt":
            continue
        source = path.read_text(encoding="utf-8")
        for method, name in re.findall(r'(?m)^\s*(get|post|put|patch|delete|head|options)\("([^"\n]*)"\)\s*\{', source):
            actual.add((method, name))
        if "get(DraftPrivacyPolicy.DOCUMENT_PATH)" in source:
            actual.add(("get", "/api/v1/legal/privacy/document"))
    documented = set()
    operation_ids = set()
    for path, item in document["paths"].items():
        for method, operation in item.items():
            if method not in METHODS:
                continue
            documented.add((method, path))
            assert operation["operationId"] not in operation_ids, "Duplicate operationId"
            operation_ids.add(operation["operationId"])
            assert operation.get("description"), f"Missing conditions: {method} {path}"
            assert "security" in operation, f"Explicit auth required: {method} {path}"
            declared_tags = {tag["name"] for tag in document["tags"]}
            assert len(operation.get("tags", [])) == 1 and operation["tags"][0] in declared_tags, (
                f"Each operation must belong to exactly one declared thematic group: {method} {path}"
            )
            for parameter in operation.get("parameters", []):
                if "example" in parameter:
                    example(parameter["schema"], parameter["example"], f"{method} {path} {parameter['name']}")
            for kind, media in operation.get("requestBody", {}).get("content", {}).items():
                media_examples(media, f"{method} {path} request {kind}")
            for status, response in operation["responses"].items():
                assert response.get("description"), f"Missing condition: {method} {path} {status}"
                if status in {"204", "406"}:
                    assert "content" not in response, f"{status} must not declare a body"
                else:
                    assert response.get("content"), f"Missing response body: {method} {path} {status}"
                    for kind, media in response["content"].items():
                        media_examples(media, f"{method} {path} {status} {kind}")
    assert actual == documented, f"Route drift: undocumented={actual-documented}; phantom={documented-actual}"
    for name, schema in document["components"]["schemas"].items():
        Draft202012Validator.check_schema(schema)
    print(f"PASS: OpenAPI 3.1; {len(documented)} REST operations match Ktor source; "
          f"{count} request/response/parameter examples valid; all component schemas valid")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["build", "check"])
    args = parser.parse_args()
    result = assemble()
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.action == "build":
        OUTPUT.write_text(rendered, encoding="utf-8")
        print(f"Built {OUTPUT.name}")
    else:
        assert OUTPUT.read_text(encoding="utf-8") == rendered, "Stale openapi.json; run tools/api_docs.py build"
        validate(result)
