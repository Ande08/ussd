const express = require('express');
const cors = require('cors');
const sqlite3 = require('sqlite3').verbose();
const path = require('path');
require('dotenv').config();

const app = express();
app.use(cors());
app.use(express.json());

const PORT = process.env.PORT || 3000;

// Initialize SQLite database
const dbPath = path.resolve(__dirname, 'queue.db');
const db = new sqlite3.Database(dbPath, (err) => {
    if (err) console.error('Error opening database:', err.message);
    else console.log('Connected to SQLite database.');
});

// Create transfers table
db.serialize(() => {
    db.run(`
        CREATE TABLE IF NOT EXISTS transfers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            number TEXT NOT NULL,
            amount TEXT NOT NULL,
            status TEXT DEFAULT 'PENDENTE',
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    `);
});

// Helper for DB queries
const dbRun = (sql, params = []) => new Promise((resolve, reject) => {
    db.run(sql, params, function (err) {
        if (err) reject(err); else resolve(this);
    });
});
const dbGet = (sql, params = []) => new Promise((resolve, reject) => {
    db.get(sql, params, (err, row) => {
        if (err) reject(err); else resolve(row);
    });
});

// --- API ROUTES ---

// 1. Bot Endpoint: Add new request to queue
app.post('/api/transfer', async (req, res) => {
    const { number, amount } = req.body;
    if (!number || !amount) {
        return res.status(400).json({ error: 'Número e Quantidade são obrigatórios.' });
    }

    try {
        const result = await dbRun(
            `INSERT INTO transfers (number, amount, status) VALUES (?, ?, 'PENDENTE')`,
            [number, amount]
        );
        res.status(201).json({ id: result.lastID, message: 'Pedido adicionado à fila.', number, amount });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao salvar pedido.' });
    }
});

// 2. App Endpoint: Fetch next pending request
app.get('/api/transfer/pending', async (req, res) => {
    try {
        // Find the oldest pending request
        const pending = await dbGet(
            `SELECT * FROM transfers WHERE status = 'PENDENTE' ORDER BY created_at ASC LIMIT 1`
        );

        if (!pending) {
            return res.json({ message: 'Nenhum pedido pendente.', job: null });
        }

        // Lock it by marking as PROCESSANDO
        await dbRun(
            `UPDATE transfers SET status = 'PROCESSANDO', updated_at = CURRENT_TIMESTAMP WHERE id = ?`,
            [pending.id]
        );

        res.json({ job: pending });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao buscar pedidos.' });
    }
});

// 3. App Endpoint: Report status (SUCCESS/FAILURE)
app.post('/api/transfer/update', async (req, res) => {
    const { id, status } = req.body; // status: 'SUCESSO', 'FALHA'
    if (!id || !status) {
        return res.status(400).json({ error: 'ID e Status são obrigatórios.' });
    }

    try {
        await dbRun(
            `UPDATE transfers SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?`,
            [status, id]
        );
        res.json({ message: `Pedido ${id} atualizado para ${status}.` });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao atualizar pedido.' });
    }
});

// Start Server
app.listen(PORT, () => {
    console.log(`Servidor USSD-Backend rodando na porta ${PORT}`);
});
