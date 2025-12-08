# -*- coding: utf-8 -*-
import os
os.environ['OAUTHLIB_RELAX_TOKEN_SCOPE'] = '1'
import time
import random
import base64
import json
import datetime
import webbrowser
import sys

from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

# --- КОНФИГУРАЦИЯ ---
CLIENT_SECRETS_FILE = "client_secret.json"
SCOPES = [
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/cloudplatformprojects.readonly",
    "https://www.googleapis.com/auth/service.management"
]

# --- ЛОГИРОВАНИЕ ---
def log(message, level="INFO"):
    timestamp = datetime.datetime.now().strftime("%H:%M:%S")
    colors = {
        "INFO": "\033[94m", "SUCCESS": "\033[92m", "WARN": "\033[93m",
        "ERROR": "\033[91m", "ACTION": "\033[96m", "WAIT": "\033[90m"
    }
    reset = "\033[0m"
    icon = {"INFO": "🔹", "WARN": "⚠️", "ERROR": "❌", "SUCCESS": "✅", "WAIT": "⏳", "ACTION": "👉"}.get(level, "🔹")
    col = colors.get(level, "")
    print(f"{col}[{timestamp}] {icon} {message}{reset}")

# --- АВТОРИЗАЦИЯ ---
def login_google():
    log("Вход в Google аккаунт...", "INFO")
    creds = None
    token_file = "token.json"
    if os.path.exists(token_file):
        try: creds = Credentials.from_authorized_user_file(token_file, SCOPES)
        except: pass

    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            try: creds.refresh(Request())
            except: creds = None
        if not creds:
            if not os.path.exists(CLIENT_SECRETS_FILE):
                log(f"Файл {CLIENT_SECRETS_FILE} не найден!", "ERROR")
                return None
            flow = InstalledAppFlow.from_client_secrets_file(CLIENT_SECRETS_FILE, SCOPES)
            creds = flow.run_local_server(port=0)
        with open(token_file, "w") as token: token.write(creds.to_json())
    return creds

# --- ВЫБОР ПРОЕКТА ---
def select_project(creds):
    crm = build("cloudresourcemanager", "v3", credentials=creds)
    try:
        results = crm.projects().search(query="state:ACTIVE").execute()
        projects = results.get('projects', [])
        
        print("\n\033[96m--- ВЫБОР ОБЛАЧНОГО ПРОЕКТА ---\033[0m")
        print("0. [ СОЗДАТЬ НОВЫЙ ПРОЕКТ ]")
        
        valid_projects = []
        if projects:
            for idx, p in enumerate(projects):
                pid = p.get('projectId')
                name = p.get('displayName', 'No Name')
                print(f"{idx + 1}. {name} ({pid})")
                valid_projects.append(pid)
        else:
            print("(Нет активных проектов)")
            
        print("\033[96m-------------------------------\033[0m")
        
        while True:
            choice = input("Ваш выбор (0 - новый): ").strip()
            if choice == '0':
                return None
            try:
                idx = int(choice) - 1
                if 0 <= idx < len(valid_projects):
                    return valid_projects[idx]
            except ValueError:
                pass
            print("Неверный номер.")
            
    except Exception as e:
        log(f"Ошибка получения списка: {e}", "WARN")
        return None

def get_project_details(creds, project_id):
    crm = build("cloudresourcemanager", "v3", credentials=creds)
    app_name = "My Chat"
    family_id = "unknown"
    try:
        p = crm.projects().get(name=f"projects/{project_id}").execute()
        app_name = p.get('displayName', app_name)
    except: pass
    
    parts = project_id.split('-')
    if len(parts) >= 3 and parts[0] == 'chat' and parts[-1].isdigit():
        family_id = "-".join(parts[1:-1])
    else:
        family_id = project_id.replace("-", "").replace("_", "")[-10:]
    return app_name, family_id

def verify_project_access(creds, project_id):
    """
    Verifies if the current credentials have access to the project.
    Returns True if accessible, False otherwise.
    """
    crm = build("cloudresourcemanager", "v3", credentials=creds)
    try:
        crm.projects().get(name=f"projects/{project_id}").execute()
        return True
    except HttpError as e:
        # 403 Forbidden or 404 Not Found means we can't use this project
        if e.resp.status in [403, 404]:
            return False
        # For other errors, we assume it might be accessible but something else is wrong
        # But to be safe for this specific "Project does not exist" issue, let's log and return False if unsure
        log(f"Error verifying project access: {e}", "WARN")
        return False
    except Exception as e:
        log(f"Unexpected error verifying project: {e}", "WARN")
        return False

# --- TOS HANDLER ---
# --- TOS HANDLER ---
TOS_CALLBACK = None

def handle_tos_error(error_msg, project_id, service_type=None):
    """
    Handles TOS errors.
    :param error_msg: The error message or exception.
    :param project_id: The project ID.
    :param service_type: Optional. "gcp" or "firebase". If set, forces specific TOS URL.
    """
    msg = str(error_msg).lower()
    # Added "permission" to catch 403 Permission Denied errors which often mean TOS not accepted
    if any(x in msg for x in ["terms", "precondition", "consumer", "service usage", "check failed", "permission"]):
        log("🛑 ТРЕБУЕТСЯ РУЧНОЕ ВМЕШАТЕЛЬСТВО (TOS) 🛑", "WARN")
        
        url = ""
        is_firebase = False
        
        # If service_type is explicitly provided, use it
        if service_type == "firebase":
            is_firebase = True
        elif service_type == "gcp":
            is_firebase = False
        else:
            # Fallback to heuristic
            if "firebase" in msg or "check failed" in msg:
                is_firebase = True
            else:
                is_firebase = False

        if is_firebase:
            url = f"https://console.firebase.google.com/project/{project_id}/overview"
            print(f"\n>>> ВЕРОЯТНО, НУЖНО ПРИНЯТЬ УСЛОВИЯ FIREBASE.")
        else:
            url = f"https://console.cloud.google.com/home/dashboard?project={project_id}"
            print(f"\n>>> ВЕРОЯТНО, НУЖНО ПРИНЯТЬ УСЛОВИЯ GOOGLE CLOUD.")
            
        if TOS_CALLBACK:
            # Bot mode: call callback and wait (or raise exception to be handled)
            # Here we expect TOS_CALLBACK to return True if resolved, or raise/return False
            return TOS_CALLBACK(url)
        else:
            # CLI mode
            webbrowser.open(url)
            input(">>> Нажмите ENTER ПОСЛЕ того, как приняли условия... <<<")
            return True
    return False

# --- СОЗДАНИЕ ---
def create_new_project(creds, app_name, family_id):
    pid = f"chat-{family_id}-{random.randint(1000,9999)}"
    log(f"Создание проекта: {pid}", "INFO")
    
    crm = build("cloudresourcemanager", "v3", credentials=creds)
    body = {"projectId": pid, "displayName": app_name}
    op_name = None
    try:
        op = crm.projects().create(body=body).execute()
        op_name = op.get('name')
        log("Запрос отправлен. Ждем (15 сек)...", "WAIT")
        time.sleep(15) 
    except HttpError as e:
        if e.resp.status == 409: return pid
        if handle_tos_error(e, pid, service_type="gcp"): 
            if wait_for_active(crm, pid): return pid
        log(f"Ошибка создания: {e}", "ERROR")
        return None

    if op_name:
        for i in range(30):
            try:
                op_st = crm.operations().get(name=op_name).execute()
                if op_st.get('done'):
                    if 'error' in op_st: handle_tos_error(op_st['error'], pid, service_type="gcp")
                    break
                time.sleep(2)
            except: time.sleep(3)
            
    if wait_for_active(crm, pid): return pid
    return None

def wait_for_active(crm, pid):
    for _ in range(30):
        try:
            state = crm.projects().get(name=f"projects/{pid}").execute().get("state")
            if state == "ACTIVE": return True
        except: pass
        time.sleep(2)
    return True

# --- API HELPERS ---
def ensure_service_enabled(su, project_id, service_name):
    full_name = f"projects/{project_id}/services/{service_name}"
    try:
        status = su.services().get(name=full_name).execute()
        if status.get("state") == "ENABLED": return True
    except: pass
    
    try:
        op = su.services().enable(name=full_name).execute()
        for _ in range(20):
            if op.get("done"): break
            time.sleep(1)
            try: op = su.operations().get(name=op['name']).execute()
            except: pass
        return True
    except: return False

# --- API SETUP ---
def enable_apis(creds, project_id):
    log("Проверка API (Service Usage)...", "INFO")
    su = build("serviceusage", "v1", credentials=creds)
    
    ensure_service_enabled(su, project_id, "serviceusage.googleapis.com")

    apis = [
        "firebase.googleapis.com", 
        "firestore.googleapis.com", 
        "iam.googleapis.com", 
        "apikeys.googleapis.com", 
        "fcm.googleapis.com", 
        "cloudresourcemanager.googleapis.com",
        "firebaserules.googleapis.com"
    ]
    
    try:
        op = su.services().batchEnable(parent=f"projects/{project_id}", body={"serviceIds": apis}).execute()
        while not op.get('done'): 
            time.sleep(2)
            op = su.operations().get(name=op['name']).execute()
    except Exception as e:
        # Force GCP TOS check here because enabling APIs is a GCP action
        if handle_tos_error(e, project_id, service_type="gcp"): 
            try: su.services().batchEnable(parent=f"projects/{project_id}", body={"serviceIds": apis}).execute()
            except: pass
        
    return True

# --- ПРАВИЛА БД ---
def configure_rules(creds, project_id):
    log("Открытие прав БД (Allow All)...", "INFO")
    rules_service = build('firebaserules', 'v1', credentials=creds)
    
    # Retry loop for propagation delays
    for attempt in range(10):
        try:
            rules_content = "rules_version = '2';\nservice cloud.firestore {\n  match /databases/{database}/documents {\n    match /{document=**} {\n      allow read, write: if true;\n    }\n  }\n}"
            ruleset_body = {"source": {"files": [{"content": rules_content, "name": "firestore.rules"}]}}
            ruleset = rules_service.projects().rulesets().create(name=f"projects/{project_id}", body=ruleset_body).execute()
            
            release_name = f"projects/{project_id}/releases/cloud.firestore"
            try: rules_service.projects().releases().update(name=release_name, body={"rulesetName": ruleset['name']}).execute()
            except: rules_service.projects().releases().create(name=f"projects/{project_id}", body={"name": release_name, "rulesetName": ruleset['name']}).execute()
            
            log("Правила БД успешно обновлены.", "SUCCESS")
            return
        except Exception as e:
            log(f"Попытка {attempt+1}/10 установки правил не удалась: {e}", "WARN")
            time.sleep(5)
            
    log("Не удалось установить правила БД после всех попыток.", "ERROR")

# --- FIREBASE RES ---
def setup_firebase_resources(creds, project_id):
    fb = build("firebase", "v1beta1", credentials=creds)
    fs = build("firestore", "v1", credentials=creds)
    
    log("Проверка Firebase...", "INFO")
    firebase_active = False
    for attempt in range(5):
        try:
            op = fb.projects().addFirebase(project=f"projects/{project_id}", body={}).execute()
            while not fb.operations().get(name=op['name']).execute().get('done'): time.sleep(1)
            firebase_active = True
            break
        except HttpError as e:
            if e.resp.status == 409:
                firebase_active = True
                break
            
            # Force Firebase TOS check here
            # User requested to prioritize Firebase link for Firebase activation errors
            if handle_tos_error(e, project_id, service_type="firebase"): continue

            log(f"Ошибка активации Firebase: {e}", "ERROR")
            time.sleep(5)

    if not firebase_active: return False

    log("Проверка БД Firestore...", "INFO")
    for attempt in range(5):
        try:
            op = fs.projects().databases().create(parent=f"projects/{project_id}", databaseId="(default)", body={"type": "FIRESTORE_NATIVE", "locationId": "nam5"}).execute()
            while not fs.projects().databases().operations().get(name=op['name']).execute().get('done'): time.sleep(1)
            break
        except HttpError as e:
            if e.resp.status == 409: break
            if handle_tos_error(e, project_id): continue
            time.sleep(5)
            
    return True

def finalize_app(creds, project_id, app_name, family_id, gs_path, sa_path):
    fb = build("firebase", "v1beta1", credentials=creds)
    iam = build("iam", "v1", credentials=creds)
    crm = build("cloudresourcemanager", "v1", credentials=creds)
    pkg = f"com.family.messenger.{family_id}"
    
    ensure_api_key(creds, project_id)
    
    log(f"Регистрация Android App: {pkg}", "INFO")
    try:
        existing = fb.projects().androidApps().list(parent=f"projects/{project_id}").execute()
        found = False
        if 'apps' in existing:
            for app in existing['apps']:
                if app.get('packageName') == pkg: found = True; break
        if not found:
            op = fb.projects().androidApps().create(parent=f"projects/{project_id}", body={"displayName": app_name, "packageName": pkg}).execute()
            while not fb.operations().get(name=op['name']).execute().get('done'): time.sleep(1)
    except HttpError as e:
        if not handle_tos_error(e, project_id): log(f"Ошибка регистрации приложения: {e}", "ERROR")

    log("Скачивание google-services.json...", "WAIT")
    time.sleep(3)
    file_downloaded = False
    for i in range(20):
        try:
            apps = fb.projects().androidApps().list(parent=f"projects/{project_id}").execute()
            tid = None
            if 'apps' in apps:
                for app in apps['apps']:
                    if app.get('packageName') == pkg: tid = app['name'].split("/")[-1]; break
            
            if tid:
                cfg = fb.projects().androidApps().getConfig(name=f"projects/{project_id}/androidApps/{tid}/config").execute()
                with open(gs_path, "wb") as f: f.write(base64.b64decode(cfg['configFileContents']))
                file_downloaded = True
                break
            time.sleep(2)
        except: time.sleep(2)
        
    if not file_downloaded:
        log("Не удалось скачать конфиг.", "ERROR")
        return False, ""

    # 1. Create Service Account
    sa = "app-bot-admin" # Changed name to avoid collisions with old/deleted accounts
    sa_email = f"{sa}@{project_id}.iam.gserviceaccount.com"
    
    # Ensure old file is gone
    if os.path.exists(sa_path): 
        os.remove(sa_path)
        log("Удален старый файл ключа перед генерацией.", "INFO")
    
    # Check if SA exists first
    sa_exists = False
    try:
        iam.projects().serviceAccounts().get(name=f"projects/{project_id}/serviceAccounts/{sa_email}").execute()
        sa_exists = True
        log(f"Сервисный аккаунт найден: {sa_email}", "INFO")
    except HttpError as e:
        if e.resp.status != 404:
            log(f"Ошибка проверки SA: {e}", "WARN")

    if not sa_exists:
        try:
            iam.projects().serviceAccounts().create(
                name=f"projects/{project_id}", 
                body={"accountId": sa, "serviceAccount": {"displayName": "App Bot Admin"}}
            ).execute()
            log(f"Создан сервисный аккаунт: {sa_email}", "SUCCESS")
        except HttpError as e:
            if e.resp.status == 409:
                log(f"Сервисный аккаунт уже существует (409): {sa_email}", "INFO")
            else:
                log(f"Ошибка создания SA: {e}", "ERROR")
                return False, ""

    time.sleep(2)

    # 2. Update IAM Policy
    log("Выдача прав (Firebase Admin)...", "INFO")
    
    roles_to_add = [
        "roles/owner", 
        "roles/firebase.admin", 
        "roles/datastore.user", 
        "roles/iam.serviceAccountTokenCreator"
    ]
    
    for attempt in range(3):
        try:
            policy = crm.projects().getIamPolicy(
                resource=project_id, 
                body={}
            ).execute()
            
            bindings = policy.get('bindings', [])
            policy_changed = False
            
            for role in roles_to_add:
                member = f"serviceAccount:{sa_email}"
                binding = next((b for b in bindings if b['role'] == role), None)
                
                if binding:
                    if member not in binding.get('members', []):
                        binding.setdefault('members', []).append(member)
                        policy_changed = True
                else:
                    bindings.append({"role": role, "members": [member]})
                    policy_changed = True
            
            if policy_changed:
                crm.projects().setIamPolicy(resource=project_id, body={'policy': policy}).execute()
                log("Права доступа успешно обновлены.", "SUCCESS")
            else:
                log("Права уже назначены.", "INFO")
            break
        except Exception as e:
            log(f"Ошибка обновления IAM (попытка {attempt+1}): {e}", "WARN")
            time.sleep(2)

    # 3. Create Key (with cleanup)
    try:
        sa_full_name = f"projects/{project_id}/serviceAccounts/{sa_email}"
        
        # List existing keys
        keys_list = iam.projects().serviceAccounts().keys().list(name=sa_full_name).execute()
        keys = keys_list.get('keys', [])
        
        # If limit reached (10), delete oldest user-managed keys
        if len(keys) >= 10:
            log(f"Достигнут лимит ключей ({len(keys)}). Удаляем старые...", "WARN")
            # Filter for USER_MANAGED keys only (system keys cannot be deleted)
            user_keys = [k for k in keys if k.get('keyType') == 'USER_MANAGED']
            # Sort by validAfterTime (creation time)
            user_keys.sort(key=lambda x: x.get('validAfterTime', ''))
            
            # Delete up to 5 oldest keys to free up space
            for k_to_del in user_keys[:5]:
                try:
                    iam.projects().serviceAccounts().keys().delete(name=k_to_del['name']).execute()
                    log(f"Удален старый ключ: {k_to_del['name'].split('/')[-1]}", "INFO")
                except Exception as e:
                    log(f"Ошибка удаления ключа: {e}", "WARN")

        # Create new key
        k = iam.projects().serviceAccounts().keys().create(
            name=sa_full_name, 
            body={"privateKeyType": "TYPE_GOOGLE_CREDENTIALS_FILE"}
        ).execute()
        with open(sa_path, "wb") as f: f.write(base64.b64decode(k['privateKeyData']))
        log(f"Ключ сохранен: {sa_path}", "SUCCESS")
        
        # Wait for propagation
        log("Ждем 5 сек для активации ключа...", "WAIT")
        time.sleep(5)
        
    except Exception as e:
        log(f"Ошибка создания ключа: {e}", "ERROR")
        return False, "" # Fail if Key creation fails
    
    return True, f"https://console.firebase.google.com/project/{project_id}/firestore"

def ensure_api_key(creds, project_id):
    service = build("apikeys", "v2", credentials=creds)
    parent = f"projects/{project_id}/locations/global"
    try:
        response = service.projects().locations().keys().list(parent=parent).execute()
        if "keys" in response and len(response["keys"]) > 0: return True
        key_body = {"displayName": "Auto Firebase Key", "restrictions": {}}
        op = service.projects().locations().keys().create(parent=parent, body=key_body).execute()
        while not service.operations().get(name=op['name']).execute().get('done'): time.sleep(1)
    except: pass

def run_setup_process(creds, pid, app_name, family_id, gs_path, sa_path):
    if not enable_apis(creds, pid): return False, ""
    if not setup_firebase_resources(creds, pid): return False, ""
    configure_rules(creds, pid)
    return finalize_app(creds, pid, app_name, family_id, gs_path, sa_path)

if __name__ == "__main__":
    # Example usage for testing
    creds = login_google()
    if creds:
        print("Logged in successfully")