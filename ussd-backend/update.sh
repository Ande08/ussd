#!/bin/bash

echo "🔄 Atualizando USSD Backend..."

# Puxar as novidades do GitHub
git pull origin main

# Instalar novas dependencias, se houver
npm install

# Reiniciar o servidor no PM2
pm2 restart ecosystem.config.js

echo "✅ USSD Backend atualizado e reiniciado com sucesso!"
