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

// Helper for DB queries (Promises)
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
const dbAll = (sql, params = []) => new Promise((resolve, reject) => {
    db.all(sql, params, (err, rows) => {
        if (err) reject(err); else resolve(rows);
    });
});

// Create tables
db.serialize(() => {
    db.run(`
        CREATE TABLE IF NOT EXISTS transfers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            number TEXT NOT NULL,
            amount TEXT NOT NULL,
            status TEXT DEFAULT 'PENDENTE',
            locked_by TEXT,
            locked_at DATETIME,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    `);

    db.run(`
        CREATE TABLE IF NOT EXISTS devices (
            username TEXT PRIMARY KEY,
            password TEXT NOT NULL,
            name TEXT,
            balance TEXT,
            paused BOOLEAN DEFAULT 0,
            battery INTEGER DEFAULT 100,
            last_seen DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    `);

    // Auto-migrate to add battery if it doesn't exist
    db.run("ALTER TABLE devices ADD COLUMN battery INTEGER DEFAULT 100", (err) => {
        if (!err) console.log("Added battery column to devices table.");
    });

    // Insert a default admin device for immediate testing if it doesn't exist
    db.run(`INSERT OR IGNORE INTO devices (username, password, name) VALUES ('admin', 'admin123', 'Celular Principal')`);
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

// 2. App Endpoint: Device Login
app.post('/api/device/login', async (req, res) => {
    const { username, password } = req.body;
    if (!username || !password) return res.status(400).json({ error: 'Usuário e senha são obrigatórios.' });

    try {
        const device = await dbGet(`SELECT * FROM devices WHERE username = ? AND password = ?`, [username, password]);
        if (device) {
            await dbRun(`UPDATE devices SET last_seen = CURRENT_TIMESTAMP WHERE username = ?`, [username]);
            res.json({ success: true, message: 'Login efetuado com sucesso', name: device.name });
        } else {
            res.status(401).json({ success: false, error: 'Credenciais inválidas' });
        }
    } catch (err) {
        res.status(500).json({ error: 'Erro interno no login' });
    }
});

// 3. App Endpoint: Status Heartbeat
app.post('/api/device/status', async (req, res) => {
    const { username, balance, paused, battery } = req.body;
    if (!username) return res.status(400).json({ error: 'Username é obrigatório.' });

    try {
        await dbRun(
            `UPDATE devices SET balance = ?, paused = ?, battery = ?, last_seen = CURRENT_TIMESTAMP WHERE username = ?`,
            [String(balance), paused ? 1 : 0, battery !== undefined ? battery : 100, username]
        );
        res.json({ success: true });
    } catch (err) {
        res.status(500).json({ error: 'Erro ao atualizar status do dispositivo.' });
    }
});

// 4. App Endpoint: Fetch next pending request (Concurreny Safe)
app.post('/api/transfer/pending', async (req, res) => {
    const { username } = req.body;
    if (!username) return res.status(400).json({ error: 'Username é obrigatório para pedir trabalhos.' });

    try {
        // Find device's available balance to filter jobs it can't handle
        const device = await dbGet(`SELECT balance FROM devices WHERE username = ?`, [username]);
        if (!device) return res.status(400).json({ error: 'Dispositivo não encontrado.' });

        const deviceBalance = parseFloat(device.balance || 0);

        // Atomic transaction: Find the oldest PENDENTE and lock it immediately for this username
        await dbRun("BEGIN EXCLUSIVE TRANSACTION");

        // Only pick jobs that the device has enough balance to process
        const pending = await dbGet(
            `SELECT * FROM transfers WHERE status = 'PENDENTE' AND CAST(amount AS REAL) <= ? ORDER BY created_at ASC LIMIT 1`,
            [deviceBalance]
        );

        if (!pending) {
            await dbRun("COMMIT");
            return res.json({ message: 'Nenhum pedido pendente.', job: null });
        }

        // Lock it
        await dbRun(
            `UPDATE transfers SET status = 'PROCESSANDO', locked_by = ?, locked_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?`,
            [username, pending.id]
        );

        await dbRun("COMMIT");

        res.json({ job: pending });
    } catch (err) {
        await dbRun("ROLLBACK").catch(() => { });
        console.error("Lock error:", err);
        res.status(500).json({ error: 'Erro ao buscar pedidos.' });
    }
});

// 5. App Endpoint: Report status (SUCCESS/FAILURE)
app.post('/api/transfer/update', async (req, res) => {
    const { id, status, username } = req.body; // status: 'SUCESSO', 'FALHA'
    if (!id || !status || !username) {
        return res.status(400).json({ error: 'ID, Status e Username são obrigatórios.' });
    }

    try {
        // Verify this user actually owns this lock (optional security step)
        const job = await dbGet(`SELECT status, locked_by FROM transfers WHERE id = ?`, [id]);
        if (!job || job.status !== 'PROCESSANDO' || job.locked_by !== username) {
            return res.status(400).json({ error: 'Este pedido não está bloqueado por você.' });
        }

        await dbRun(
            `UPDATE transfers SET status = ?, locked_by = NULL, locked_at = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?`,
            [status, id]
        );
        res.json({ message: `Pedido ${id} atualizado para ${status}.` });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao atualizar pedido.' });
    }
});

// 6. Dashboard Endpoint: View all devices
app.get('/api/devices', async (req, res) => {
    try {
        const devices = await dbAll(`SELECT username, name, balance, paused, battery, last_seen FROM devices`);
        res.json({ devices });
    } catch (err) {
        res.status(500).json({ error: 'Erro ao buscar dispositivos.' });
    }
});

// --- BACKGROUND WORKERS ---

// Unlock stalled jobs every 1 minute
setInterval(async () => {
    try {
        // Find jobs in PROCESSANDO state whose locked_at time is older than 2 minutes ago
        const twoMinutesAgo = new Date(Date.now() - 2 * 60 * 1000).toISOString().replace("T", " ").replace("Z", "");

        const stalledJobs = await dbAll(`SELECT id FROM transfers WHERE status = 'PROCESSANDO' AND locked_at < ?`, [twoMinutesAgo]);

        if (stalledJobs.length > 0) {
            console.log(`[Auto-Unlock] Encontrados ${stalledJobs.length} pedidos travados. Retornando para a fila.`);
            for (let job of stalledJobs) {
                await dbRun(
                    `UPDATE transfers SET status = 'PENDENTE', locked_by = NULL, locked_at = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?`,
                    [job.id]
                );
            }
        }
    } catch (err) {
        console.error("[Auto-Unlock] Erro ao destravar pedidos:", err.message);
    }
}, 60000); // 60 seconds

// Start Server
app.listen(PORT, () => {
    console.log(`Servidor USSD-Backend rodando na porta ${PORT}`);
});
