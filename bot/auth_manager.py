import os
import json
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import Flow
from google.auth.transport.requests import Request

# Scopes required by the bot
SCOPES = [
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/cloudplatformprojects.readonly",
    "https://www.googleapis.com/auth/service.management"
]

CLIENT_SECRETS_FILE = "client_secret.json" # Assumed to be in the root

class AuthManager:
    def __init__(self, users_dir="users"):
        self.users_dir = users_dir
        if not os.path.exists(self.users_dir):
            os.makedirs(self.users_dir)

    def get_user_dir(self, user_id):
        path = os.path.join(self.users_dir, str(user_id))
        if not os.path.exists(path):
            os.makedirs(path)
        return path

    def get_token_path(self, user_id):
        return os.path.join(self.get_user_dir(user_id), "token.json")

    def get_creds(self, user_id):
        token_path = self.get_token_path(user_id)
        creds = None
        if os.path.exists(token_path):
            try:
                creds = Credentials.from_authorized_user_file(token_path, SCOPES)
            except Exception:
                return None
        
        if creds and creds.expired and creds.refresh_token:
            try:
                creds.refresh(Request())
                # Save refreshed token
                with open(token_path, "w") as token:
                    token.write(creds.to_json())
            except Exception:
                return None
                
        return creds

    def get_auth_url(self, user_id, redirect_uri):
        """
        Returns (auth_url, flow)
        """
        if not os.path.exists(CLIENT_SECRETS_FILE):
            raise FileNotFoundError(f"Client secrets file {CLIENT_SECRETS_FILE} not found.")

        flow = Flow.from_client_secrets_file(
            CLIENT_SECRETS_FILE,
            scopes=SCOPES,
            redirect_uri=redirect_uri
        )
        
        # Enable offline access to get refresh token
        auth_url, _ = flow.authorization_url(
            prompt='consent',
            access_type='offline',
            include_granted_scopes='true',
            state=str(user_id)
        )
        return auth_url, flow

    def finish_auth(self, user_id, flow, code):
        flow.fetch_token(code=code)
        creds = flow.credentials
        
        token_path = self.get_token_path(user_id)
        with open(token_path, "w") as token:
            token.write(creds.to_json())
        
        return creds
