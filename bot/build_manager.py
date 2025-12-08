import os
import shutil
import secrets
import subprocess
import time
import logging
import sys
from PIL import Image

# Add parent directory to path to import firebase_auto
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import firebase_auto

logger = logging.getLogger(__name__)

class BuildManager:
    def __init__(self, project_root, builds_dir="temp_builds"):
        self.project_root = os.path.abspath(project_root)
        self.builds_dir = os.path.abspath(builds_dir)
        if not os.path.exists(self.builds_dir):
            os.makedirs(self.builds_dir)

    def run_build(self, user_id, creds, app_name, family_id, aes_key, icon_path=None, existing_project_id=None, turn_config=None):
        build_id = f"{user_id}_{int(time.time())}"
        build_dir = os.path.join(self.builds_dir, build_id)
        
        logger.info(f"Starting build {build_id} for user {user_id}")
        
        try:
            # 1. Copy Project
            self._copy_project(build_dir)
            
            # 2. Setup Paths
            path_gs = os.path.join(build_dir, "app", "google-services.json")
            path_sa = os.path.join(build_dir, "app", "src", "main", "assets", "service-account.json")
            os.makedirs(os.path.dirname(path_sa), exist_ok=True)
            
            # 3. Firebase Setup
            project_id = None
            if existing_project_id:
                logger.info(f"Verifying access to existing project: {existing_project_id}")
                if firebase_auto.verify_project_access(creds, existing_project_id):
                    logger.info(f"Access confirmed. Using existing project: {existing_project_id}")
                    project_id = existing_project_id
                else:
                    logger.warning(f"Cannot access project {existing_project_id}. It may not exist or belong to another account. Creating a new one.")
                    existing_project_id = None # Reset so we create a new one

            if not project_id:
                # Use family_id as display name to ensure it's valid (alphanumeric)
                # app_name might contain special chars that Google Cloud rejects for project names
                project_id = firebase_auto.create_new_project(creds, family_id, family_id)
                if not project_id:
                    raise Exception("Failed to create or select Firebase project.")
                
            success, _ = firebase_auto.run_setup_process(creds, project_id, app_name, family_id, path_gs, path_sa)
            if not success:
                raise Exception("Failed to setup Firebase resources.")
                
            # Verify SA key was actually generated
            if not os.path.exists(path_sa):
                 raise Exception("Service account key not generated. Build cannot proceed.")
            
            # Log SA details for debugging
            try:
                import json
                with open(path_sa, 'r') as f:
                    sa_data = json.load(f)
                    logger.info(f"SA Key Generated. Project: {sa_data.get('project_id')}, Email: {sa_data.get('client_email')}")
            except Exception as e:
                logger.warning(f"Could not read SA file for logging: {e}")

            # 4. Icon Injection
            if icon_path and os.path.exists(icon_path):
                self._inject_icon(build_dir, icon_path)

            # 5. Inject App Name
            self._inject_app_name(build_dir, app_name)

            # 6. Build
            # Pass aes_key explicitly
            apk_path = self._exec_gradle(build_dir, app_name, family_id, aes_key, turn_config)
            
            return apk_path, project_id, build_dir

        except Exception as e:
            logger.error(f"Build failed: {e}")
            # Check if build log exists and attach it to exception or return it
            log_path = os.path.join(build_dir, "build.log")
            if os.path.exists(log_path):
                e.log_path = log_path
            # Attach build_dir to exception for cleanup
            e.build_dir = build_dir
            raise e


        finally:
            pass

    def cleanup_build(self, build_dir):
        """Deletes the temporary build directory."""
        if build_dir and os.path.exists(build_dir):
            try:
                shutil.rmtree(build_dir)
                logger.info(f"Cleaned up build directory: {build_dir}")
            except Exception as e:
                logger.error(f"Failed to cleanup build directory {build_dir}: {e}")

    def _copy_project(self, target_dir):
        # We only need specific files/folders
        ignore = shutil.ignore_patterns("build", ".gradle", "temp_builds", "bot", ".git", "*.iml", "local.properties")
        shutil.copytree(self.project_root, target_dir, ignore=ignore)
        
        # Ensure gradlew is executable
        gradlew = os.path.join(target_dir, "gradlew")
        if os.path.exists(gradlew):
            os.chmod(gradlew, 0o755)

    def _inject_icon(self, build_dir, icon_path):
        res_dir = os.path.join(build_dir, "app", "src", "main", "res")
        
        # Delete adaptive icon folder to force Android to use the PNGs we inject
        adaptive_dir = os.path.join(res_dir, "mipmap-anydpi-v26")
        if os.path.exists(adaptive_dir):
            shutil.rmtree(adaptive_dir)
            logger.info("Deleted adaptive icon folder to force custom icon usage.")
        
        # Map folder name to size (px)
        sizes = {
            "mipmap-mdpi": 48,
            "mipmap-hdpi": 72,
            "mipmap-xhdpi": 96,
            "mipmap-xxhdpi": 144,
            "mipmap-xxxhdpi": 192
        }
        
        try:
            original = Image.open(icon_path)
            
            for folder, size in sizes.items():
                folder_path = os.path.join(res_dir, folder)
                if not os.path.exists(folder_path):
                    os.makedirs(folder_path)
                
                # Resize
                resized = original.resize((size, size), Image.Resampling.LANCZOS)
                
                # Save as ic_launcher.png
                resized.save(os.path.join(folder_path, "ic_launcher.png"), "PNG")
                
                # Save as ic_launcher_round.png (optional, but good practice)
                resized.save(os.path.join(folder_path, "ic_launcher_round.png"), "PNG")
                
            logger.info(f"Icon injected successfully from {icon_path}")
        except Exception as e:
            logger.error(f"Failed to inject icon: {e}")
            # Don't fail the build, just log error

    def _inject_app_name(self, build_dir, app_name):
        # Explicitly write app_name to strings.xml
        strings_path = os.path.join(build_dir, "app", "src", "main", "res", "values", "strings.xml")
        
        try:
            # Simple XML construction to avoid parsing issues
            content = f"""<resources>
    <string name="app_name">{app_name}</string>
    <string name="channel_incoming_calls_name">Входящие звонки</string>
    <string name="channel_incoming_calls_desc">Уведомления о входящих звонках</string>
    <string name="channel_ongoing_calls_name">Идёт звонок</string>
    <string name="channel_ongoing_calls_desc">Постоянное уведомление активного звонка</string>

    <string name="incoming_audio_call_title">Входящий аудиозвонок</string>
    <string name="incoming_video_call_title">Входящий видеозвонок</string>

    <string name="ongoing_audio_call_title">Аудиозвонок</string>
    <string name="ongoing_video_call_title">Видеозвонок</string>

    <string name="call_accept">Принять</string>
    <string name="call_decline">Отклонить</string>
    <string name="action_open">Open</string>
    <string name="action_answer">Answer</string>
    <string name="action_decline">Decline</string>
    <string name="action_hangup">Завершить</string>
    <string name="action_mute">Микрофон</string>
    <string name="action_speaker">Динамик</string>
    <string name="action_video">Камера</string>

    <string name="call_ongoing_audio">Идёт аудиозвонок</string>
    <string name="call_ongoing_video">Идёт видеозвонок</string>
    <string name="tap_to_return">нажмите, чтобы вернуться</string>
</resources>"""
            
            with open(strings_path, "w") as f:
                f.write(content)
                
            logger.info(f"App name '{app_name}' injected into strings.xml")
        except Exception as e:
            logger.error(f"Failed to inject app name: {e}")
            raise e


    def _exec_gradle(self, build_dir, app_name, family_id, aes_key, turn_config=None):
        gradle_cmd = "./gradlew" if os.name != 'nt' else "gradlew.bat"
        
        env = os.environ.copy()
        env["APP_ID"] = f"com.family.messenger.{family_id}"
        env["APP_NAME"] = app_name
        env["AES_SECRET"] = aes_key
        
        # Added --stacktrace and --info for better debugging
        cmd = [os.path.join(build_dir, gradle_cmd), "assembleDebug", "--stacktrace", "--info"]
        
        if turn_config:
            cmd.append(f"-PturnConfig={turn_config}")
        
        logger.info(f"Running Gradle in {build_dir}...")
        
        log_path = os.path.join(build_dir, "build.log")
        
        with open(log_path, "w") as log_file:
            process = subprocess.Popen(
                cmd, 
                cwd=build_dir, 
                env=env, 
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, # Merge stderr into stdout
                text=True,
                bufsize=1,
                universal_newlines=True
            )
            
            # Stream output to console and file
            for line in process.stdout:
                print(line, end='') # Print to console
                log_file.write(line) # Write to file
                log_file.flush()
                
            process.wait()
        
        if process.returncode != 0:
            logger.error(f"Gradle Error. See {log_path}")
            raise Exception("Gradle build failed. See build.log for details.")
            
        # Find APK
        apk_output_dir = os.path.join(build_dir, "app", "build", "outputs", "apk", "debug")
        apk_name = "app-debug.apk"
        full_path = os.path.join(apk_output_dir, apk_name)
        
        if os.path.exists(full_path):
            return full_path
        else:
            raise Exception("APK file not found after successful build.")
