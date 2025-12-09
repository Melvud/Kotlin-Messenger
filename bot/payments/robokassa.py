import hashlib
import os
from urllib.parse import urlencode

# Configuration
MERCHANT_LOGIN = os.getenv("MERCHANT_LOGIN")
PASSWORD_1 = os.getenv("PASSWORD_1")
PASSWORD_2 = os.getenv("PASSWORD_2")
IS_TEST = os.getenv("ROBOKASSA_IS_TEST", "1")  # Default to test mode

def generate_payment_link(user_id: int, amount: float, description: str = "Payment") -> str:
    """
    Generates a Robokassa payment link.
    """
    if not MERCHANT_LOGIN or not PASSWORD_1:
        raise ValueError("MERCHANT_LOGIN and PASSWORD_1 must be set in environment variables.")

    inv_id = str(user_id)  # Using user_id as invoice ID for simplicity, or generate a unique one
    # Note: In a real system, inv_id should be a unique transaction ID, and we should map it to user_id.
    # For this task, the requirement says "Find user by InvId (this will be user_id)", so we follow that.
    
    # Signature: login:out_sum:inv_id:password_1
    signature_string = f"{MERCHANT_LOGIN}:{amount}:{inv_id}:{PASSWORD_1}"
    signature = hashlib.md5(signature_string.encode('utf-8')).hexdigest()

    params = {
        "MerchantLogin": MERCHANT_LOGIN,
        "OutSum": str(amount),
        "InvId": inv_id,
        "Description": description,
        "SignatureValue": signature,
        "IsTest": IS_TEST
    }
    
    base_url = "https://auth.robokassa.ru/Merchant/Index.aspx"
    return f"{base_url}?{urlencode(params)}"

def validate_signature(out_sum: str, inv_id: str, signature: str, is_result: bool = True) -> bool:
    """
    Validates the signature from Robokassa.
    is_result: True for ResultURL (uses Password2), False for SuccessURL (uses Password1)
    """
    password = PASSWORD_2 if is_result else PASSWORD_1
    if not password:
         raise ValueError(f"PASSWORD_{'2' if is_result else '1'} must be set in environment variables.")

    # Signature: out_sum:inv_id:password
    signature_string = f"{out_sum}:{inv_id}:{password}"
    my_signature = hashlib.md5(signature_string.encode('utf-8')).hexdigest()

    return my_signature.lower() == signature.lower()
