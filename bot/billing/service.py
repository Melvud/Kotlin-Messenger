import os
import json
import logging
import time
from datetime import datetime, timedelta
from bot.auth_manager import AuthManager

logger = logging.getLogger(__name__)
auth_manager = AuthManager()

# Default subscription duration in days
SUBSCRIPTION_DAYS = 30

def mark_user_paid(user_id: int) -> bool:
    """
    Marks the user as paid (Lifetime access).
    """
    try:
        user_dir = auth_manager.get_user_dir(user_id)
        if not os.path.exists(user_dir):
            logger.error(f"User directory not found for {user_id}")
            return False

        state_path = os.path.join(user_dir, "state.json")
        state = {}
        
        if os.path.exists(state_path):
            try:
                with open(state_path, "r") as f:
                    state = json.load(f)
            except json.JSONDecodeError:
                logger.warning(f"Corrupted state file for {user_id}, creating new one.")

        state["is_paid"] = True
        # Removed paid_until logic for lifetime access
        
        with open(state_path, "w") as f:
            json.dump(state, f)
            
        logger.info(f"User {user_id} marked as paid (Lifetime).")
        return True
        
    except Exception as e:
        logger.error(f"Failed to mark user {user_id} as paid: {e}")
        return False

def is_user_paid(user_id: int) -> bool:
    """
    Checks if the user has paid (Lifetime).
    """
    try:
        user_dir = auth_manager.get_user_dir(user_id)
        state_path = os.path.join(user_dir, "state.json")
        
        if os.path.exists(state_path):
            with open(state_path, "r") as f:
                state = json.load(f)
                return state.get("is_paid", False)
                
        return False
    except Exception as e:
        logger.error(f"Failed to check payment status for {user_id}: {e}")
        return False

def get_subscription_status(user_id: int) -> str:
    """
    Returns a human-readable subscription status.
    """
    try:
        user_dir = auth_manager.get_user_dir(user_id)
        state_path = os.path.join(user_dir, "state.json")
        
        if os.path.exists(state_path):
            with open(state_path, "r") as f:
                state = json.load(f)
                if state.get("is_paid", False):
                    return "✅ Доступ: **Навсегда**"
        
        return "❌ Не оплачено"
    except Exception as e:
        logger.error(f"Failed to get subscription status for {user_id}: {e}")
        return "⚠️ Ошибка проверки статуса"
