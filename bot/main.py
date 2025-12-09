import logging
import os
os.environ['OAUTHLIB_RELAX_TOKEN_SCOPE'] = '1'
import sys
import threading
import asyncio
import json
import time
import random
import string
import secrets
from fastapi import FastAPI, Request, Response
import uvicorn
from dotenv import load_dotenv
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup, ReplyKeyboardMarkup, KeyboardButton
from telegram.ext import Application, CommandHandler, MessageHandler, filters, ContextTypes, ConversationHandler, CallbackQueryHandler

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bot.auth_manager import AuthManager
from bot.build_manager import BuildManager
from bot.payments import robokassa
from bot.billing import service as billing_service
import firebase_auto

load_dotenv()

# Enable logging
logging.basicConfig(
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s", level=logging.INFO
)
logger = logging.getLogger(__name__)

# Config
PORT = 8080
PUBLIC_URL = os.getenv("PUBLIC_URL", "http://localhost:8080")
SUBSCRIPTION_PRICE = int(os.getenv("SUBSCRIPTION_PRICE", 1000)) 

# States
APP_NAME = 1
ICON = 2
TURN_CONFIG = 3

# Global Managers
auth_manager = AuthManager()
build_manager = BuildManager(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# TOS Handling
tos_events = {} # user_id -> threading.Event

# Auth Flows Storage (user_id -> flow)
auth_flows = {}

# Menu Keyboard
MAIN_MENU = ReplyKeyboardMarkup(
    [
        [KeyboardButton("📖 Инструкция"), KeyboardButton("🚀 Создать приложение")],
        [KeyboardButton("📂 Мои приложения"), KeyboardButton("👤 Профиль")],
        [KeyboardButton("⚙️ Расширенные настройки"), KeyboardButton("📞 Контакты")],
        [KeyboardButton("💳 Купить доступ"), KeyboardButton("❓ FAQ")]
    ],
    resize_keyboard=True
)

def get_user_state(user_id):
    path = os.path.join(auth_manager.get_user_dir(user_id), "state.json")
    if os.path.exists(path):
        with open(path, "r") as f:
            return json.load(f)
    return None

def save_user_state(user_id, state):
    path = os.path.join(auth_manager.get_user_dir(user_id), "state.json")
    with open(path, "w") as f:
        json.dump(state, f)

def tos_callback_factory(bot, chat_id, loop):
    def callback(url):
        user_id = chat_id
        event = threading.Event()
        tos_events[user_id] = event
        
        keyboard = [[InlineKeyboardButton("✅ Я принял условия", callback_data="tos_done")]]
        reply_markup = InlineKeyboardMarkup(keyboard)
        
        # Send message (async from thread)
        asyncio.run_coroutine_threadsafe(
            bot.send_message(
                chat_id=chat_id,
                text=f"⚠️ **Требуется действие!**\n\nGoogle требует принять условия использования.\n\n👉 [Перейти и принять]({url})\n\nПосле принятия нажмите кнопку ниже.",
                parse_mode="Markdown",
                reply_markup=reply_markup
            ),
            loop
        )
        
        # Wait for event
        logger.info(f"Waiting for TOS acceptance from {user_id}...")
        event.wait()
        return True
    return callback

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    keyboard = [
        [InlineKeyboardButton("📖 Инструкция", callback_data="instruction_inline")],
        [InlineKeyboardButton("🚀 Создать приложение", callback_data="create_app_inline")]
    ]
    
    await update.message.reply_text(
    await update.message.reply_text(
        "👋 **Привет! Я бот-конструктор мессенджеров.**\n\n"
        "🇷🇺 **Предыстория:**\n"
        "Я создал это приложение для общения со своей семьей в России. Из-за постоянных блокировок и нестабильной работы привычных мессенджеров, нам нужно было что-то надежное, свое и независимое.\n\n"
        "☕️ Я решил поделиться этой разработкой с вами за символическую цену в пару чашек кофе, чтобы вы тоже могли обеспечить стабильную связь своим близким.\n\n"
        "🤔 **Что я умею?**\n"
        "Я автоматически настраиваю ваши личные сервера, базы данных и собираю готовое Android приложение, которое принадлежит только вам.\n\n"
        "👇 **С чего начать?**\n"
        "Если ты здесь впервые, нажми **«📖 Инструкция»** — я расскажу, как это работает.\n"
        "Если готов — жми **«🚀 Создать приложение»**!",
        parse_mode="Markdown",
        reply_markup=InlineKeyboardMarkup(keyboard)
    )
    
    # Ensure ReplyKeyboard is visible
    await update.message.reply_text("👇 Меню бота:", reply_markup=MAIN_MENU)
    
    return ConversationHandler.END

async def menu_instruction(update: Update, context: ContextTypes.DEFAULT_TYPE):
    # Handle CallbackQuery if present
    if update.callback_query:
        await update.callback_query.answer()
        message_func = update.callback_query.message.reply_text
    else:
        message_func = update.message.reply_text

    text = (
        "📖 **ПОДРОБНАЯ ИНСТРУКЦИЯ**\n\n"
        "Этот бот создает для вас **личный мессенджер**, который принадлежит только вам. Ниже описаны шаги и важные моменты.\n\n"
        "1️⃣ **Авторизация через Google**\n"
        "Мы попросим вас войти в Google аккаунт. Это нужно, чтобы создать **Ваш личный проект в Google Cloud и Firebase**.\n"
        "— **Зачем?** Мессенджер требует серверную часть (базу данных, пуш-уведомления). Мы не используем общий сервер — мы создаем его лично для вас.\n"
        "— **Безопасность:** Доступ к базе данных будет **только у вас**. Ни я (разработчик бота), ни другие пользователи не имеют к ней доступа.\n"
        "— **Разрешения:** Бот запросит права на управление Cloud Platform и Firebase. Это необходимо для автоматической настройки серверов.\n\n"
        "2️⃣ **Настройка приложения**\n"
        "Вы придумаете название и загрузите иконку. Бот соберет APK файл специально для вас.\n\n"
        "3️⃣ **Условия использования**\n"
        "В процессе сборки Google может попросить принять соглашения (Terms of Service) для использования Firebase. Бот пришлет ссылку — нужно перейти по ней, нажать «Принять», и подтвердить это в боте.\n\n"
        "4️⃣ **Расширенные настройки (TURN)**\n"
        "По умолчанию звонки работают через P2P (напрямую) или через мои собственные сервера. В разделе «Расширенные настройки» вы можете подключить **свои TURN сервера**.\n"
        "— Это нужно для полной независимости и приватности звонков.\n"
        "— Если вы хотите создать полностью автономный мессенджер, не зависящий ни от кого — это ваш выбор.\n\n"
        "🚀 **Готовы?** Жмите кнопку **«Создать приложение»** в главном меню!"
    )
    await message_func(text, parse_mode="Markdown")

async def menu_profile(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    creds = auth_manager.get_creds(user_id)
    
    status = "✅ Подключен" if creds else "❌ Не подключен"
    
    keyboard = []
    if creds:
        keyboard.append([InlineKeyboardButton("❌ Отвязать Google аккаунт", callback_data="unlink_google")])
    
    await update.message.reply_text(
        f"👤 **Профиль**\n\n"
        f"ID: `{user_id}`\n"
        f"Google Аккаунт: {status}",
        parse_mode="Markdown",
        reply_markup=InlineKeyboardMarkup(keyboard) if keyboard else None
    )

async def unlink_google_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    
    user_id = update.effective_user.id
    
    # Remove token
    token_path = auth_manager.get_token_path(user_id)
    if os.path.exists(token_path):
        os.remove(token_path)
        
    await query.edit_message_text(text="✅ Google аккаунт успешно отвязан.")

async def menu_buy(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    
    # Generate payment link
    try:
        payment_url = robokassa.generate_payment_link(user_id, float(SUBSCRIPTION_PRICE), "Подписка на конструктор")
        
        keyboard = [[InlineKeyboardButton("💳 Оплатить", url=payment_url)]]
        
        await update.message.reply_text(
            f"💳 **Оплата доступа**\n\n"
            f"Стоимость: **{SUBSCRIPTION_PRICE} ₽**\n"
            f"Срок: **Навсегда**\n\n"
            "После оплаты вы сможете создавать неограниченное количество приложений.",
            parse_mode="Markdown",
            reply_markup=InlineKeyboardMarkup(keyboard)
        )
    except Exception as e:
        logger.error(f"Error generating payment link: {e}")
        await update.message.reply_text("⚠️ Ошибка при создании ссылки на оплату. Попробуйте позже.")

async def menu_status(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    status_text = billing_service.get_subscription_status(user_id)
    
    keyboard = []
    if "Не оплачено" in status_text:
        keyboard.append([InlineKeyboardButton("💳 Купить подписку", callback_data="buy_subscription")])
        
    await update.message.reply_text(
        f"📊 **Статус подписки**\n\n{status_text}",
        parse_mode="Markdown",
        reply_markup=InlineKeyboardMarkup(keyboard) if keyboard else None
    )

async def buy_callback_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    # Call menu_buy but we need to handle the update correctly
    # Since menu_buy expects update.message, we need to adapt or just send a new message
    user_id = update.effective_user.id
    try:
        payment_url = robokassa.generate_payment_link(user_id, float(SUBSCRIPTION_PRICE), "Подписка на конструктор")
        keyboard = [[InlineKeyboardButton("💳 Оплатить", url=payment_url)]]
        await query.message.reply_text(
            f"💳 **Оплата доступа**\n\n"
            f"Стоимость: **{SUBSCRIPTION_PRICE} ₽**\n"
            f"Срок: **Навсегда**",
            parse_mode="Markdown",
            reply_markup=InlineKeyboardMarkup(keyboard)
        )
    except Exception as e:
        logger.error(f"Error generating payment link: {e}")
        await query.message.reply_text("⚠️ Ошибка при создании ссылки на оплату.")


async def menu_create_app(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    user_id = update.effective_user.id
    
    # Check payment status
    if not billing_service.is_user_paid(user_id):
        await update.message.reply_text(
            "⛔️ **Доступ запрещен**\n\n"
            "Для создания приложения необходима активная подписка.\n"
            "Пожалуйста, оплатите доступ.",
            parse_mode="Markdown"
        )
        # Call menu_buy logic here or just guide user
        await menu_buy(update, context)
        return ConversationHandler.END

    creds = auth_manager.get_creds(user_id)
    
    # Determine reply function
    if update.callback_query:
        await update.callback_query.answer()
        reply_func = update.callback_query.message.reply_text
    else:
        reply_func = update.message.reply_text
    
    if not creds:
        # Start Auth Flow
        redirect_uri = f"{PUBLIC_URL}/oauth2callback"
        logger.info(f"Generating auth URL with redirect_uri: {redirect_uri}")
        auth_url, flow = auth_manager.get_auth_url(user_id, redirect_uri)
        auth_flows[user_id] = flow
        
        await reply_func(
            "🔑 **Требуется авторизация**\n\n"
            "Для создания приложения необходим доступ к Google Cloud (Firebase).\n"
            "Пожалуйста, авторизуйтесь по ссылке ниже:\n\n"
            f"👉 [Авторизоваться]({auth_url})\n\n"
            "После успешного входа вы получите уведомление.",
            parse_mode="Markdown"
        )
        return ConversationHandler.END
    
    await reply_func(
        "📝 **Название приложения**\n\n"
        "Введите название для вашего мессенджера (например, 'My Family Chat'):",
        reply_markup=ReplyKeyboardMarkup([["Отмена"]], resize_keyboard=True)
    )
    return APP_NAME

async def menu_my_apps(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    state = get_user_state(user_id)
    
    if not state or not state.get('last_apk_path'):
        await update.message.reply_text("📂 У вас пока нет созданных приложений.")
        return
        
    app_name = state.get('app_name', 'Неизвестно')
    family_id = state.get('family_id', 'Неизвестно')
    timestamp = state.get('timestamp', 0)
    date_str = time.strftime('%Y-%m-%d %H:%M', time.localtime(timestamp))
    aes_key = state.get('last_aes_key', 'Неизвестно')
    
    await update.message.reply_text(
        f"📂 **Последнее приложение**\n\n"
        f"📱 Название: {app_name}\n"
        f"🆔 ID: `{family_id}`\n"
        f"📅 Дата: {date_str}\n"
        f"🔑 Ключ: `{aes_key}`",
        parse_mode="Markdown"
    )
    
    # Check if APK exists and send it
    apk_path = state.get('last_apk_path')
    if apk_path and os.path.exists(apk_path):
         await update.message.reply_document(document=open(apk_path, 'rb'), caption="📦 Ваш APK файл")


async def menu_contacts(update: Update, context: ContextTypes.DEFAULT_TYPE):
    keyboard = [[InlineKeyboardButton("🆘 Написать @drmelvud", url="https://t.me/drmelvud")]]
    await update.message.reply_text(
        "📞 **Контакты**\n\n"
        "Если вам нужна помощь или у вас есть вопросы, пишите мне:",
        reply_markup=InlineKeyboardMarkup(keyboard)
    )

async def menu_advanced_settings(update: Update, context: ContextTypes.DEFAULT_TYPE):
    keyboard = [
        [InlineKeyboardButton("ℹ️ Помощь (TURN)", callback_data="turn_help")],
        [InlineKeyboardButton("🌐 Настроить TURN сервера", callback_data="setup_turn")]
    ]
    await update.message.reply_text(
        "⚙️ **Расширенные настройки**\n\n"
        "Здесь вы можете настроить параметры подключения для звонков (WebRTC).",
        reply_markup=InlineKeyboardMarkup(keyboard)
    )

async def menu_faq(update: Update, context: ContextTypes.DEFAULT_TYPE):
    keyboard = [
        [InlineKeyboardButton("🍎 Когда на iOS?", callback_data="faq_ios")],
        [InlineKeyboardButton("🔒 Безопасность", callback_data="faq_security")]
    ]
    
    # Handle CallbackQuery if present (for "back" buttons if we add them later)
    if update.callback_query:
        await update.callback_query.answer()
        reply_func = update.callback_query.message.reply_text
    else:
        reply_func = update.message.reply_text

    await reply_func(
        "❓ **Частые вопросы**\n\n"
        "Выберите вопрос, чтобы узнать ответ:",
        reply_markup=InlineKeyboardMarkup(keyboard)
    )

async def faq_callback_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    
    data = query.data
    
    if data == "faq_ios":
        text = (
            "🍎 **Версия для iOS**\n\n"
            "Версия для iPhone сейчас находится в активной разработке.\n"
            "К сожалению, разработка под iOS требует больше времени и финансовых вложений (лицензии Apple, оборудование).\n\n"
            "Мы стараемся выпустить её как можно скорее! 🚀"
        )
    elif data == "faq_security":
        text = (
            "🔒 **Безопасность**\n\n"
            "Ваша безопасность — наш приоритет:\n\n"
            "1️⃣ **Звонки (WebRTC):**\n"
            "Все звонки защищены сквозным шифрованием (DTLS/SRTP). Никто (даже я) не может их прослушать.\n\n"
            "2️⃣ **Сообщения:**\n"
            "База данных находится в **вашем** личном проекте Firebase. Доступ к ней есть только у вас. Мы используем правила безопасности Firestore, чтобы никто посторонний не мог читать вашу переписку."
        )
    else:
        text = "Информация не найдена."
        
    # Add a "Back to FAQ" button
    keyboard = [[InlineKeyboardButton("🔙 Назад к вопросам", callback_data="faq_back")]]
    
    await query.message.reply_text(text, parse_mode="Markdown", reply_markup=InlineKeyboardMarkup(keyboard))

async def faq_back_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    # Just call menu_faq but we need to handle the update correctly as it comes from a callback
    await menu_faq(update, context)

async def turn_help_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    
    text = (
        "ℹ️ **ЧТО ТАКОЕ TURN СЕРВЕРА?**\n\n"
        "**Простыми словами:**\n"
        "Когда вы звоните кому-то, телефон пытается соединиться напрямую. Но если вы за NAT (роутером) или фаерволом, прямое соединение может не пройти. В этом случае нужен посредник — TURN сервер.\n\n"
        "**Зачем ставить свои?**\n"
        "1. **Приватность:** Весь трафик звонков будет идти через ВАШ сервер, а не через мои или публичные.\n"
        "2. **Независимость:** Вы не зависите от сторонних сервисов.\n"
        "3. **Стабильность:** Вы сами контролируете нагрузку.\n\n"
        "**Как создать свой TURN сервер? (Пошагово)**\n"
        "1. Арендуйте любой дешевый VPS (сервер) на Ubuntu.\n"
        "2. Подключитесь к нему и установите `coturn`: `apt install coturn`.\n"
        "3. Настройте конфиг `/etc/turnserver.conf` (укажите user, password, realm).\n"
        "4. Запустите сервер.\n"
        "5. Введите данные в этом боте: `turn:IP:PORT user password`.\n\n"
        "Если это звучит сложно — не переживайте, приложение будет работать и без этого (через мои сервера)."
    )
    
    keyboard = [[InlineKeyboardButton("🆘 Нужна помощь", url="https://t.me/drmelvud")]]
    
    await query.message.reply_text(text, parse_mode="Markdown", reply_markup=InlineKeyboardMarkup(keyboard))

async def ask_turn_servers(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    query = update.callback_query
    await query.answer()
    
    user_id = update.effective_user.id
    state = get_user_state(user_id)
    current_config = state.get('turn_config', 'Не задано') if state else 'Не задано'
    
    await query.message.reply_text(
        f"🌐 **Настройка TURN серверов**\n\n"
        f"Текущая конфигурация:\n`{current_config}`\n\n"
        "Отправьте список серверов в формате:\n"
        "`uri username password`\n\n"
        "Пример:\n"
        "`turn:my-server.com:3478 myuser mypass`\n"
        "`stun:stun.l.google.com:19302`\n\n"
        "Каждый сервер с новой строки. Нажмите 'Отмена' для выхода или 'Сбросить' для удаления настроек.",
        parse_mode="Markdown",
        reply_markup=ReplyKeyboardMarkup([["Сбросить"], ["Отмена"]], resize_keyboard=True)
    )
    return TURN_CONFIG

async def set_turn_servers(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    text = update.message.text
    user_id = update.effective_user.id
    state = get_user_state(user_id) or {}
    
    if text == "Отмена":
        await update.message.reply_text("❌ Отменено.", reply_markup=MAIN_MENU)
        return ConversationHandler.END
        
    if text == "Сбросить":
        if 'turn_config' in state:
            del state['turn_config']
            save_user_state(user_id, state)
        await update.message.reply_text("✅ Настройки TURN сброшены. Используются сервера по умолчанию.", reply_markup=MAIN_MENU)
        return ConversationHandler.END

    # Basic validation
    lines = text.split('\n')
    valid_lines = []
    for line in lines:
        parts = line.strip().split(' ')
        if len(parts) >= 1 and (parts[0].startswith('turn:') or parts[0].startswith('turns:') or parts[0].startswith('stun:')):
            valid_lines.append(line.strip())
            
    if not valid_lines:
        await update.message.reply_text(
            "⚠️ **Некорректный формат!**\n\n"
            "Строки должны начинаться с `turn:`, `turns:` или `stun:`.\n"
            "Попробуйте еще раз или нажмите 'Отмена'.",
            parse_mode="Markdown"
        )
        return TURN_CONFIG
        
    config_str = '\n'.join(valid_lines)
    state['turn_config'] = config_str
    save_user_state(user_id, state)
    
    await update.message.reply_text(
        "✅ **TURN сервера сохранены!**\n\n"
        "Они будут использованы при следующей сборке приложения.",
        reply_markup=MAIN_MENU
    )
    return ConversationHandler.END

async def app_name(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    name = update.message.text
    if name == "Отмена":
        return await cancel(update, context)
        
    context.user_data['app_name'] = name
    
    # Check for existing family_id in state to ensure consistent App ID
    user_id = update.effective_user.id
    state = get_user_state(user_id)
    
    if state and state.get('family_id'):
        family_id = state['family_id']
        # Ensure family_id starts with a letter (Android package name requirement)
        if family_id and family_id[0].isdigit():
            logger.info(f"Invalid family_id (starts with digit): {family_id}. Regenerating...")
            family_id = random.choice(string.ascii_lowercase) + ''.join(random.choices(string.ascii_lowercase + string.digits, k=7))
            state['family_id'] = family_id
            save_user_state(user_id, state)
        else:
            logger.info(f"Reusing existing family_id: {family_id} for user {user_id}")
    else:
        # Generate random family ID (ensure starts with letter)
        family_id = random.choice(string.ascii_lowercase) + ''.join(random.choices(string.ascii_lowercase + string.digits, k=7))
        
    context.user_data['family_id'] = family_id
    
    await update.message.reply_text(
        f"✅ Название принято: **{name}**\n"
        f"🆔 ID семьи: `{family_id}`\n\n"
        "Теперь отправьте **иконку приложения** (изображение) или нажмите 'Пропустить', чтобы использовать стандартную.",
        parse_mode="Markdown",
        reply_markup=ReplyKeyboardMarkup([["Пропустить"], ["Отмена"]], resize_keyboard=True)
    )
    return ICON

async def icon(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    photo_file = await update.message.photo[-1].get_file()
    
    # Save icon
    user_id = update.effective_user.id
    icon_dir = os.path.join("users", str(user_id))
    if not os.path.exists(icon_dir):
        os.makedirs(icon_dir)
        
    icon_path = os.path.join(icon_dir, "icon.png")
    await photo_file.download_to_drive(icon_path)
    
    context.user_data['icon_path'] = icon_path
    
    await start_build(update, context)
    return ConversationHandler.END

async def skip_icon(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    context.user_data['icon_path'] = None
    await start_build(update, context)
    return ConversationHandler.END

async def start_build(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    app_name = context.user_data['app_name']
    family_id = context.user_data['family_id']
    icon_path = context.user_data.get('icon_path')
    creds = auth_manager.get_creds(user_id)
    
    await update.message.reply_text(f"🚀 **Начинаю сборку**\n\nПриложение: {app_name}\nID: {family_id}\n\nЭто займет несколько минут...", parse_mode="Markdown", reply_markup=MAIN_MENU)
    
    # Get current loop to pass to thread
    loop = asyncio.get_running_loop()
    
    # Get turn_config from state
    state = get_user_state(user_id)
    turn_config = state.get('turn_config') if state else None
    
    # Run build in a separate thread
    thread = threading.Thread(target=run_build_thread, args=(update.effective_chat.id, context.application, user_id, creds, app_name, family_id, icon_path, turn_config, loop))
    thread.start()

def run_build_thread(chat_id, application, user_id, creds, app_name, family_id, icon_path, turn_config, loop):
    logger.info(f"Thread started for user {user_id}")
    # Set TOS callback for this thread
    firebase_auto.TOS_CALLBACK = tos_callback_factory(application.bot, chat_id, loop)
    
    # Retrieve or generate persistent AES key for this user
    state = get_user_state(user_id)
    if state and state.get('aes_key'):
        aes_key = state['aes_key']
    else:
        aes_key = secrets.token_hex(32)
        if state:
            state['aes_key'] = aes_key
            save_user_state(user_id, state)
        else:
            save_user_state(user_id, {'aes_key': aes_key})
            
    # Save AES key to users/{user_id}/aes_secret.txt
    try:
        user_dir = auth_manager.get_user_dir(user_id)
        aes_secret_path = os.path.join(user_dir, "aes_secret.txt")
        with open(aes_secret_path, "w") as f:
            f.write(aes_key)
        logger.info(f"Saved AES key to {aes_secret_path}")
    except Exception as e:
        logger.error(f"Failed to save aes_secret.txt file: {e}")
            
    # Retrieve existing project_id if available
    existing_project_id = state.get('firebase_project_id') if state else None
    
    # Initialize build_dir to None for cleanup in case of early failure
    build_dir = None

    try:
        logger.info(f"Calling build_manager.run_build for {app_name}...")
        # Pass aes_key, existing_project_id, and turn_config to run_build
        apk_path, project_id, build_dir = build_manager.run_build(user_id, creds, app_name, family_id, aes_key, icon_path, existing_project_id, turn_config)
        logger.info(f"Build successful. APK path: {apk_path}, Project ID: {project_id}")
        
        # Save state (update last build info and project_id)
        new_state = get_user_state(user_id) or {}
        new_state.update({
            "last_apk_path": apk_path,
            "last_aes_key": aes_key,
            "app_name": app_name,
            "family_id": family_id,
            "firebase_project_id": project_id, # Save project ID for reuse
            "timestamp": time.time()
        })
        save_user_state(user_id, new_state)
        logger.info("User state saved.")

        # Check file size
        file_size = os.path.getsize(apk_path)
        logger.info(f"APK size: {file_size / (1024*1024):.2f} MB")
        
        # Send APK normally
        logger.info("Scheduling send_document coroutine...")
        future = asyncio.run_coroutine_threadsafe(
            application.bot.send_document(
                chat_id=chat_id, 
                document=open(apk_path, 'rb'),
                caption=f"✅ **Сборка завершена!**,
                parse_mode="Markdown",
                reply_markup=MAIN_MENU,
                read_timeout=300, 
                write_timeout=300,
                connect_timeout=60
            ),
            loop
        )
        # Wait for the result to catch any async errors
        try:
            future.result(timeout=300) # Wait up to 5 minutes
            logger.info("Document sent successfully.")
        except Exception as send_err:
            logger.error(f"Failed to send document: {send_err}")
            raise send_err
            
    except Exception as e:
        logger.error(f"Build thread failed: {e}")
        
        # Capture build_dir from exception if available (set by build_manager)
        if hasattr(e, 'build_dir'):
            build_dir = e.build_dir
        
        # Log error to console only, do NOT send file to user
        log_file = getattr(e, 'log_path', None)
        if log_file and os.path.exists(log_file):
            try:
                with open(log_file, 'r') as f:
                    log_content = f.read()
                    logger.error(f"--- BUILD LOG START ---\n{log_content}\n--- BUILD LOG END ---")
            except Exception as read_err:
                logger.error(f"Could not read log file: {read_err}")
        
        logger.info("Sending error message to user...")
        asyncio.run_coroutine_threadsafe(
            application.bot.send_message(
                chat_id=chat_id, 
                text=f"❌ **Ошибка сборки**: {str(e)}\n\nОбратитесь к администратору.",
                parse_mode="Markdown",
                reply_markup=MAIN_MENU
            ),
            loop
        )
        
    finally:
        # Always cleanup build directory
        if build_dir:
            logger.info(f"Cleaning up build directory: {build_dir}")
            build_manager.cleanup_build(build_dir)

async def tos_done_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    
    user_id = update.effective_user.id
    if user_id in tos_events:
        tos_events[user_id].set()
        await query.edit_message_text("✅ Спасибо! Продолжаю сборку...")
    else:
        await query.edit_message_text("⚠️ Сессия истекла или не найдена.")

async def cancel(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    await update.message.reply_text("❌ Отменено.", reply_markup=MAIN_MENU)
    return ConversationHandler.END

# FastAPI App
app = FastAPI()

@app.get("/")
async def root():
    return Response(content=f"<html><body><h1>Messenger Builder Bot</h1><p><a href='https://t.me/msg_creation_bot'>@msg_creation_bot</a></p><p><a href='/legal/offer'>Публичная оферта</a></p><p><a href='/legal/privacy'>Политика конфиденциальности</a></p></body></html>", media_type="text/html")

@app.get("/legal/offer")
async def legal_offer():
    try:
        with open("legal/offer_ru.md", "r") as f:
            text = f.read()
        # Simple markdown to HTML conversion for display
        html = f"<html><body><pre>{text}</pre></body></html>"
        return Response(content=html, media_type="text/html")
    except FileNotFoundError:
        return Response(content="Document not found", status_code=404)

@app.get("/legal/privacy")
async def legal_privacy():
    try:
        with open("legal/privacy_ru.md", "r") as f:
            text = f.read()
        html = f"<html><body><pre>{text}</pre></body></html>"
        return Response(content=html, media_type="text/html")
    except FileNotFoundError:
        return Response(content="Document not found", status_code=404)

@app.get("/robokassa/result")
async def robokassa_result(request: Request):
    params = request.query_params
    out_sum = params.get("OutSum")
    inv_id = params.get("InvId")
    signature = params.get("SignatureValue")
    
    if not out_sum or not inv_id or not signature:
        return Response(content="Bad Request", status_code=400)
        
    try:
        if robokassa.validate_signature(out_sum, inv_id, signature):
            user_id = int(inv_id)
            billing_service.mark_user_paid(user_id)
            return Response(content=f"OK{inv_id}")
        else:
            return Response(content="Bad Signature", status_code=400)
    except Exception as e:
        logger.error(f"Payment processing error: {e}")
        return Response(content="Error", status_code=500)

@app.get("/robokassa/success")
async def robokassa_success():
    return Response(content="<html><body><h1>Оплата прошла успешно!</h1><p>Вернитесь в бот <a href='https://t.me/msg_creation_bot'>@msg_creation_bot</a></p></body></html>", media_type="text/html")

@app.get("/robokassa/fail")
async def robokassa_fail():
    return Response(content="<html><body><h1>Ошибка оплаты</h1><p>Попробуйте снова в боте <a href='https://t.me/msg_creation_bot'>@msg_creation_bot</a></p></body></html>", media_type="text/html")

@app.get("/oauth2callback")
async def oauth_callback(request: Request):
    params = request.query_params
    state = params.get('state')
    code = params.get('code')
    
    if not state or not code:
        return Response(content="Missing state or code", status_code=400)
        
    try:
        user_id = int(state)
    except ValueError:
        return Response(content="Invalid state format", status_code=400)
    
    if user_id not in auth_flows:
        return Response(content="Invalid state or session expired. Please try again in the bot.", status_code=400)
        
    flow = auth_flows[user_id]
    bot_app = request.app.state.bot_app
    
    try:
        # Finish auth (blocking call, run in executor)
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, auth_manager.finish_auth, user_id, flow, code)
        
        # Cleanup flow
        del auth_flows[user_id]
        
        # Notify user on Telegram
        await bot_app.bot.send_message(
            chat_id=user_id,
            text="✅ **Авторизация успешна!**\n\nТеперь вы можете создать приложение.",
            parse_mode="Markdown",
            reply_markup=MAIN_MENU
        )
            
        return Response(content="Authentication successful! You can close this window and return to the bot.")
        
    except Exception as e:
        logger.error(f"Auth failed: {e}")
        return Response(content=f"Authentication failed: {str(e)}", status_code=500)

async def post_init(application: Application) -> None:
    app.state.bot_app = application
    config = uvicorn.Config(app, host="0.0.0.0", port=PORT, log_level="info")
    server = uvicorn.Server(config)
    # Run uvicorn in the existing loop
    asyncio.create_task(server.serve())
    logger.info(f"FastAPI server started on port {PORT}")

def main():
    token = "8262225773:AAFlvqpndU6PYIOnk7ddQlVg5R5N8bko_-E"
    application = Application.builder().token(token).post_init(post_init).read_timeout(30).write_timeout(30).connect_timeout(30).build()

    # Menu Handlers
    application.add_handler(CommandHandler("start", start))
    application.add_handler(MessageHandler(filters.Regex("^📂 Мои приложения$"), menu_my_apps))
    application.add_handler(MessageHandler(filters.Regex("^👤 Профиль$"), menu_profile))
    
    application.add_handler(MessageHandler(filters.Regex("^📞 Контакты$"), menu_contacts))
    application.add_handler(MessageHandler(filters.Regex("^📖 Инструкция$"), menu_instruction))
    application.add_handler(CallbackQueryHandler(menu_instruction, pattern="^instruction_inline$"))

    # Conversation for Create App
    conv_handler = ConversationHandler(
        entry_points=[
            MessageHandler(filters.Regex("^🚀 Создать приложение$"), menu_create_app),
            CallbackQueryHandler(menu_create_app, pattern="^create_app_inline$")
        ],
        states={
            APP_NAME: [MessageHandler(filters.TEXT & ~filters.COMMAND, app_name)],
            ICON: [
                MessageHandler(filters.PHOTO, icon),
                MessageHandler(filters.Regex("^Пропустить$"), skip_icon)
            ]
        },
        fallbacks=[MessageHandler(filters.Regex("^Отмена$"), cancel)]
    )
    application.add_handler(conv_handler)
    
    application.add_handler(MessageHandler(filters.Regex("^⚙️ Расширенные настройки$"), menu_advanced_settings))
    application.add_handler(CallbackQueryHandler(turn_help_handler, pattern="^turn_help$"))

    # FAQ Handlers
    application.add_handler(MessageHandler(filters.Regex("^❓ FAQ$"), menu_faq))
    application.add_handler(CallbackQueryHandler(faq_callback_handler, pattern="^faq_(ios|security)$"))
    application.add_handler(CallbackQueryHandler(faq_back_handler, pattern="^faq_back$"))
    
    # Conversation for TURN Setup
    turn_conv = ConversationHandler(
        entry_points=[CallbackQueryHandler(ask_turn_servers, pattern="^setup_turn$")],
        states={
            TURN_CONFIG: [MessageHandler(filters.TEXT & ~filters.COMMAND, set_turn_servers)]
        },
        fallbacks=[MessageHandler(filters.Regex("^Отмена$"), cancel)]
    )
    application.add_handler(turn_conv)

    application.add_handler(CallbackQueryHandler(unlink_google_handler, pattern="^unlink_google$"))
    application.add_handler(CallbackQueryHandler(tos_done_handler, pattern="^tos_done$"))

    # Payment Handlers
    application.add_handler(CommandHandler("buy", menu_buy))
    application.add_handler(CommandHandler("status", menu_status))
    application.add_handler(MessageHandler(filters.Regex("^💳 Купить доступ$"), menu_status))
    application.add_handler(CallbackQueryHandler(buy_callback_handler, pattern="^buy_subscription$"))

    # Run the bot
    application.run_polling()

if __name__ == "__main__":
    main()
