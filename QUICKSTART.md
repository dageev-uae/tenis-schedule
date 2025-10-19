# 🚀 Быстрый старт (5 минут)

## Чеклист для деплоя

### ✅ Шаг 1: Подготовка кода (уже сделано!)
- [x] Проект создан
- [x] PostgreSQL поддержка добавлена
- [x] Dockerfile готов
- [x] Railway конфигурация готова

### ☐ Шаг 2: Загрузка на GitHub

```bash
# 1. Добавить все файлы
git add .

# 2. Создать коммит
git commit -m "Initial commit: Tennis booking bot"

# 3. Загрузить на GitHub (если еще не сделано)
git push origin main
```

### ☐ Шаг 3: Настроить Supabase (2 минуты)

1. Откройте [supabase.com](https://supabase.com) → Sign in with GitHub
2. New Project → Заполните:
   - Name: `tennis-booking-bot`
   - Database Password: **создайте и сохраните!**
   - Region: `Frankfurt` (или ближайший к вам)
3. После создания: Settings → Database → Connection string → URI
4. Скопируйте строку (замените `[YOUR-PASSWORD]` на ваш пароль):
   ```
   postgresql://postgres:[YOUR-PASSWORD]@db.xxx.supabase.co:5432/postgres
   ```

### ☐ Шаг 4: Деплой на Railway (3 минуты)

1. Откройте [railway.app](https://railway.app) → Login with GitHub
2. **New Project** → **Deploy from GitHub repo**
3. Выберите репозиторий `tenis-schedule`
4. Кликните на сервис → вкладка **Variables**
5. Добавьте переменные:

```bash
TELEGRAM_BOT_TOKEN=ваш_токен_бота
COURT_USERNAME=ваш_email@example.com
COURT_PASSWORD=ваш_пароль
DATABASE_URL=postgresql://postgres:...  # из Supabase шаг 3
```

6. Сохраните → Railway начнет деплой автоматически!

### ☐ Шаг 5: Проверка (1 минута)

1. В Railway: **Deployments** → смотрите логи
2. Ждите сообщение: `Tennis Booking Bot started successfully!`
3. Откройте вашего бота в Telegram → `/start`
4. Протестируйте: `/schedule 2025-10-26 18:00`

---

## 🎉 Готово!

Ваш бот работает 24/7!

### Что дальше?

- 📖 Полная инструкция: [DEPLOYMENT.md](DEPLOYMENT.md)
- 📝 Документация: [README.md](README.md)
- 🐛 Проблемы? См. секцию Troubleshooting в [DEPLOYMENT.md](DEPLOYMENT.md)

### Полезные ссылки

- Railway проект: https://railway.app/project/[ваш-проект]
- Supabase проект: https://app.supabase.com/project/[ваш-проект]
- Логи Railway: Deployments → Latest deploy
- БД Supabase: Table Editor → bookings
