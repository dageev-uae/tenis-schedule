# Tennis Booking Bot

Автоматический Telegram бот для бронирования теннисных кортов ровно в полночь.

## Возможности

- **Автоматическое бронирование**: Бот автоматически бронирует корты ровно в 00:00:00
- **Telegram интерфейс**: Простое управление через команды бота
- **Планировщик**: Запускается в 23:57 и ждет до полуночи для точного бронирования
- **База данных**: SQLite для хранения запланированных бронирований
- **Уведомления**: Автоматические уведомления о статусе бронирования

## Команды бота

- `/start` - Приветствие и список команд
- `/schedule <дата> <время>` - Запланировать бронирование
  - Пример: `/schedule 2025-10-25 18:00`
- `/list` - Показать все запланированные бронирования
- `/cancel <id>` - Отменить бронирование

## Технологический стек

- **Язык**: Kotlin (JVM)
- **Telegram**: kotlin-telegram-bot
- **HTTP клиент**: Ktor Client
- **База данных**: Exposed ORM + PostgreSQL (Supabase) / SQLite (dev)
- **Планировщик**: Kotlin Coroutines
- **Деплой**: Railway
- **БД хостинг**: Supabase

## Настройка

### Переменные окружения

Создайте файл `.env` или установите следующие переменные окружения:

```bash
TELEGRAM_BOT_TOKEN=your_bot_token_here
COURT_USERNAME=your_email@example.com
COURT_PASSWORD=your_password
DATABASE_PATH=tennis_bot.db  # опционально
```

### Локальный запуск

1. Клонируйте репозиторий:
```bash
git clone <repository_url>
cd tenis-schedule
```

2. Установите переменные окружения:
```bash
export TELEGRAM_BOT_TOKEN="your_token"
export COURT_USERNAME="your_email"
export COURT_PASSWORD="your_password"
```

3. Соберите и запустите:
```bash
./gradlew build
./gradlew run
```

### Запуск через Docker

```bash
docker build -t tennis-bot .
docker run -e TELEGRAM_BOT_TOKEN="your_token" \
           -e COURT_USERNAME="your_email" \
           -e COURT_PASSWORD="your_password" \
           tennis-bot
```

## Деплой на Railway + Supabase

**Полная инструкция**: См. [DEPLOYMENT.md](DEPLOYMENT.md)

### Быстрый старт:

1. **Создайте БД в Supabase**:
   - Зарегистрируйтесь на [supabase.com](https://supabase.com)
   - Создайте новый проект
   - Получите строку подключения DATABASE_URL

2. **Деплой на Railway**:
   - Зарегистрируйтесь на [railway.app](https://railway.app)
   - Создайте проект из GitHub репозитория
   - Установите переменные окружения:
     - `TELEGRAM_BOT_TOKEN`
     - `COURT_USERNAME`
     - `COURT_PASSWORD`
     - `DATABASE_URL` (из Supabase)

3. **Railway автоматически задеплоит приложение**

Подробные инструкции с скриншотами см. в [DEPLOYMENT.md](DEPLOYMENT.md)

## Как работает планировщик

1. Каждый день в **23:57** планировщик запускается
2. Загружает все бронирования на **завтра** из базы данных
3. Авторизуется на сайте кортов
4. Ждет до **00:00:00** с точностью до миллисекунд
5. Выполняет все запланированные бронирования
6. Отправляет уведомления пользователям о результатах

## API сайта бронирования

Бот использует REST API сайта [Damac Living](https://www.damacliving.com/).

### Авторизация
```
POST https://digital.damacgroup.com/damacliving/api/v1/users/login
```

### Бронирование
> **TODO**: Необходимо получить точный эндпоинт и формат для создания бронирования кортов

## Структура проекта

```
src/main/kotlin/
├── Main.kt                      # Точка входа
├── bot/
│   └── TelegramBot.kt          # Telegram бот и команды
├── scheduler/
│   └── BookingScheduler.kt     # Планировщик бронирований
├── court/
│   └── CourtAPI.kt             # REST API клиент
└── database/
    ├── Database.kt              # Настройка БД
    └── models/
        └── Booking.kt           # Модель бронирования
```

## Логирование

Логи сохраняются в:
- `logs/tennis-bot.log` - текущий лог
- `logs/tennis-bot.YYYY-MM-DD.log` - архивные логи (хранятся 30 дней)

## TODO

- [ ] Получить точный эндпоинт API для бронирования кортов
- [ ] Добавить выбор конкретного корта (номер корта)
- [ ] Добавить команду для просмотра доступных временных слотов
- [ ] Реализовать повторные попытки при ошибках
- [ ] Добавить timezone настройки для точного определения полуночи

## Лицензия

MIT

## Автор

Tennis Booking Bot - автоматизация бронирования теннисных кортов
