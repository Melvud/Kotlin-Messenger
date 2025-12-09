import hashlib
import os
from urllib.parse import urlencode
from dotenv import load_dotenv

# Загружаем переменные, если они еще не загружены
load_dotenv()

# --- КОНФИГУРАЦИЯ ---
MERCHANT_LOGIN = os.getenv("MERCHANT_LOGIN")
BASE_URL = os.getenv("BASE_URL")

# Проверяем режим работы (1 = Тест, 0 = Боевой)
# По умолчанию считаем тестом, если не указано иное
IS_TEST = int(os.getenv("ROBOKASSA_IS_TEST", "1"))

# Логика выбора паролей
if IS_TEST == 1:
    # Берем тестовые пароли
    PASSWORD_1 = os.getenv("TEST_PASSWORD_1")
    PASSWORD_2 = os.getenv("TEST_PASSWORD_2")
else:
    # Берем боевые пароли
    PASSWORD_1 = os.getenv("PASSWORD_1")
    PASSWORD_2 = os.getenv("PASSWORD_2")

def generate_payment_link(user_id: int, cost: float, description: str) -> str:
    """
    Генерирует ссылку на оплату.
    """
    if not MERCHANT_LOGIN or not PASSWORD_1:
        raise ValueError("Ошибка: Не заданы логин или пароль Робокассы в .env")

    inv_id = str(user_id)  # ID пользователя как номер счета
    
    # Формула подписи: login:out_sum:inv_id:password_1
    # Важно: cost должен быть строкой для корректного хеша
    cost_str = str(cost)
    
    signature_txt = f"{MERCHANT_LOGIN}:{cost_str}:{inv_id}:{PASSWORD_1}"
    signature = hashlib.md5(signature_txt.encode('utf-8')).hexdigest()

    params = {
        "MerchantLogin": MERCHANT_LOGIN,
        "OutSum": cost_str,
        "InvId": inv_id,
        "Description": description,
        "SignatureValue": signature,
        "IsTest": IS_TEST,  # Передаем 1 или 0
        "Culture": "ru"     # Язык интерфейса
    }
    
    # Добавляем URL возврата, если они настроены в Nginx и BASE_URL
    # Это гарантирует, что пользователя вернет в бота после оплаты
    if BASE_URL:
        # Убедимся, что BASE_URL не заканчивается на слеш
        clean_base_url = BASE_URL.rstrip('/')
        params["ResultURL"] = f"{clean_base_url}/robokassa/result"
        params["SuccessURL"] = f"{clean_base_url}/robokassa/success"
        params["FailURL"] = f"{clean_base_url}/robokassa/fail"
    
    return f"https://auth.robokassa.ru/Merchant/Index.aspx?{urlencode(params)}"

def validate_signature(out_sum: str, inv_id: str, signature: str) -> bool:
    """
    Проверяет уведомление от Робокассы (ResultURL).
    Использует PASSWORD_2 (тестовый или боевой, зависит от IS_TEST).
    Формула: out_sum:inv_id:password_2
    """
    if not PASSWORD_2:
         raise ValueError("Ошибка: PASSWORD_2 не найден в .env")

    signature_txt = f"{out_sum}:{inv_id}:{PASSWORD_2}"
    my_signature = hashlib.md5(signature_txt.encode('utf-8')).hexdigest()
    
    return my_signature.lower() == signature.lower()