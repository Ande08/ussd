#!/bin/bash

echo "🔄 Atualizando USSD Backend..."

# 1. Backup da base de dados local para não perder dados reais
if [ -f "queue.db" ]; then
    cp queue.db queue.db.tmp
    echo "💾 Backup temporário da DB criado."
fi

# 2. Forçar a limpeza e reset do repositório para ignorar conflitos locais
git fetch --all
git reset --hard origin/main
git clean -fd

# 3. Restaurar a base de dados
if [ -f "queue.db.tmp" ]; then
    mv queue.db.tmp queue.db
    echo "📦 Base de dados restaurada."
fi

# 4. Atualizar dependências
npm install

# 5. Reiniciar servidor
pm2 restart ecosystem.config.js || pm2 start index.js --name ussd-backend

echo "✅ USSD Backend atualizado e reiniciado com sucesso!"
