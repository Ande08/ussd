#!/bin/bash

echo "🔄 Atualizando USSD Backend..."

# 1. Backup da base de dados local para /tmp (fora do alcance do git clean)
if [ -f "queue.db" ]; then
    cp queue.db /tmp/queue.db.tmp
    echo "💾 Backup de segurança criado em /tmp."
fi

# 2. Forçar a limpeza e reset do repositório
git fetch --all
git reset --hard origin/main
git clean -fd

# 3. Restaurar a base de dados de /tmp
if [ -f "/tmp/queue.db.tmp" ]; then
    mv /tmp/queue.db.tmp queue.db
    echo "📦 Base de dados restaurada com sucesso."
fi

# 4. Atualizar dependências
npm install

# 5. Reiniciar servidor
pm2 restart ecosystem.config.js || pm2 start index.js --name ussd-backend

echo "✅ USSD Backend atualizado e reiniciado com sucesso!"
