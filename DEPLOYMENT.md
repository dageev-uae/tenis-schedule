# Инструкция по деплою на Railway + Supabase

## Шаг 1: Настройка Supabase PostgreSQL

### 1.1 Создайте проект в Supabase

1. Перейдите на [supabase.com](https://supabase.com)
2. Нажмите "Start your project"
3. Войдите с помощью GitHub
4. Создайте новую организацию (если нужно)
5. Нажмите "New Project"
6. Заполните форму:
   - **Name**: tennis-booking-bot
   - **Database Password**: создайте надежный пароль (сохраните его!)
   - **Region**: выберите ближайший регион (например, Frankfurt)
   - **Pricing Plan**: Free tier
7. Нажмите "Create new project"

### 1.2 Получите строку подключения к БД

1. В левом меню выберите **Settings** (шестеренка внизу)
2. Перейдите в **Database**
3. Прокрутите до секции **Connection string**
4. Выберите вкладку **URI**
5. Скопируйте строку подключения, она выглядит так:
   ```
   postgresql://postgres:[YOUR-PASSWORD]@db.xxx.supabase.co:5432/postgres
   ```
6. Замените `[YOUR-PASSWORD]` на пароль, который вы создали в шаге 1.1

### 1.3 (Опционально) Проверьте подключение

Вы можете проверить подключение через встроенный SQL Editor в Supabase:
1. Перейдите в **SQL Editor** в левом меню
2. Нажмите "New query"
3. Выполните:
   ```sql
   SELECT version();
   ```

---

## Шаг 2: Настройка Railway

### 2.1 Создайте аккаунт в Railway

1. Перейдите на [railway.app](https://railway.app)
2. Нажмите "Start a New Project"
3. Войдите через GitHub

### 2.2 Создайте новый проект

1. Нажмите "New Project"
2. Выберите "Deploy from GitHub repo"
3. Подключите свой GitHub аккаунт (если еще не подключен)
4. Выберите репозиторий **tenis-schedule**
5. Нажмите "Deploy Now"

### 2.3 Настройте переменные окружения

1. В Railway откройте ваш проект
2. Выберите ваш сервис (должен называться **tenis-schedule**)
3. Перейдите на вкладку **Variables**
4. Нажмите **+ New Variable** и добавьте следующие переменные:

| Variable Name | Value | Описание |
|--------------|-------|----------|
| `TELEGRAM_BOT_TOKEN` | `ваш_токен_бота` | Токен вашего Telegram бота |
| `COURT_USERNAME` | `ваш_email@example.com` | Email для авторизации на сайте кортов |
| `COURT_PASSWORD` | `ваш_пароль` | Пароль для авторизации |
| `DATABASE_URL` | `postgresql://postgres:...` | Строка подключения из Supabase (шаг 1.2) |

**Важно**: Строка `DATABASE_URL` должна быть полной, включая пароль!

### 2.4 Настройте деплой

1. Перейдите на вкладку **Settings**
2. В секции **Deploy** убедитесь, что:
   - **Build Command**: пусто (используется Dockerfile)
   - **Start Command**: пусто (используется Dockerfile)
   - **Restart Policy**: Always Restart

### 2.5 Запустите деплой

1. Railway автоматически начнет деплой после сохранения переменных
2. Следите за логами на вкладке **Deployments**
3. Дождитесь сообщения "Build successful"
4. Проверьте логи: должно быть "Tennis Booking Bot started successfully!"

---

## Шаг 3: Проверка работы

### 3.1 Проверьте логи в Railway

1. Перейдите на вкладку **Deployments**
2. Кликните на последний деплой
3. Проверьте логи - должны быть сообщения:
   ```
   INFO  Main - Starting Tennis Booking Bot...
   INFO  DatabaseFactory - Initializing PostgreSQL database...
   INFO  DatabaseFactory - Database schema created successfully
   INFO  Main - Tennis Booking Bot started successfully!
   ```

### 3.2 Проверьте базу данных в Supabase

1. В Supabase перейдите в **Table Editor**
2. Должна появиться таблица `bookings` с колонками:
   - id
   - user_id
   - court_date
   - court_time
   - created_at
   - status

### 3.3 Протестируйте бота в Telegram

1. Найдите вашего бота в Telegram
2. Отправьте `/start`
3. Попробуйте создать тестовое бронирование:
   ```
   /schedule 2025-10-26 18:00
   ```
4. Проверьте список бронирований:
   ```
   /list
   ```
5. Проверьте в Supabase Table Editor - должна появиться новая запись!

---

## Шаг 4: Мониторинг и логи

### Railway логи
- В реальном времени: вкладка **Deployments** → выберите деплой → смотрите логи
- Фильтры: можно фильтровать по уровню (INFO, ERROR и т.д.)

### Supabase логи
- В Supabase: **Database** → **Logs**
- Здесь можно отслеживать SQL запросы и ошибки БД

---

## Troubleshooting

### Проблема: "Authentication failed"
**Решение**:
- Проверьте `COURT_USERNAME` и `COURT_PASSWORD` в Railway Variables
- Убедитесь, что credentials верные

### Проблема: "Database connection failed"
**Решение**:
- Проверьте `DATABASE_URL` в Railway Variables
- Убедитесь, что пароль правильно вставлен в строку подключения
- Проверьте, что проект Supabase запущен (не в режиме паузы)

### Проблема: Бот не отвечает
**Решение**:
- Проверьте логи в Railway - есть ли ошибки?
- Убедитесь, что `TELEGRAM_BOT_TOKEN` правильный
- Проверьте, что деплой успешен (зеленая галочка)

### Проблема: Бронирования не создаются в полночь
**Решение**:
- Проверьте временную зону сервера в логах
- Убедитесь, что планировщик запущен (должно быть сообщение "Starting booking scheduler...")

---

## Дополнительные настройки

### Автоматический редеплой
Railway автоматически деплоит при каждом push в main ветку GitHub

### Масштабирование
- Railway Free tier: достаточно для бота
- При необходимости можно апгрейднуть до Pro ($5/месяц)

### Бэкапы БД
Supabase автоматически создает бэкапы:
- Free tier: дневные бэкапы (хранятся 7 дней)
- Восстановление: Settings → Database → Backups

---

## Полезные ссылки

- [Railway Documentation](https://docs.railway.app)
- [Supabase Documentation](https://supabase.com/docs)
- [Telegram Bot API](https://core.telegram.org/bots/api)

---

## Готово!

Ваш бот теперь работает 24/7 на Railway с PostgreSQL от Supabase!

Он будет автоматически бронировать корты каждую ночь в полночь согласно запланированным бронированиям.
