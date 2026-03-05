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

    // Migrations
    db.run("ALTER TABLE transfers ADD COLUMN assigned_to TEXT", (err) => {
        if (!err) console.log("Added assigned_to column to transfers table.");
    });
    db.run("ALTER TABLE transfers ADD COLUMN owner_account TEXT", (err) => {
        if (!err) console.log("Added owner_account column to transfers table.");
    });

    db.run("ALTER TABLE devices ADD COLUMN battery INTEGER DEFAULT 100", (err) => {
        if (!err) console.log("Added battery column to devices table.");
    });
    db.run("ALTER TABLE devices ADD COLUMN account TEXT", (err) => {
        if (!err) console.log("Added account column to devices table.");
    });

    // Insert a default admin device for immediate testing if it doesn't exist
    db.run(`INSERT OR IGNORE INTO devices (username, password, name) VALUES ('admin', 'admin123', 'Celular Principal')`);
});

// --- API ROUTES ---

// 1. Bot Endpoint: Add new request to queue
app.post('/api/transfer', async (req, res) => {
    const { number, amount, username } = req.body; // username is the requester
    if (!number || !amount) {
        return res.status(400).json({ error: 'Número e Quantidade são obrigatórios.' });
    }

    try {
        // Find the requester's account
        let ownerAccount = null;
        if (username) {
            const requester = await dbGet(`SELECT account FROM devices WHERE username = ?`, [username]);
            ownerAccount = requester ? requester.account : null;
        }

        // --- CENTRALIZED ROUTING LOGIC (Account Aware) ---
        const twoMinutesAgo = new Date(Date.now() - 2 * 60 * 1000).toISOString().replace("T", " ").replace("Z", "");

        // Find eligible devices in the SAME account (or unassigned account if fallback needed)
        const eligibleDevices = await dbAll(`
            SELECT username, name, balance FROM devices 
            WHERE paused = 0 AND last_seen > ? AND (account = ? OR account IS NULL)
        `, [twoMinutesAgo, ownerAccount]);

        let assignedTo = null;
        let assignedName = "Fila Geral (Aguardando Dispositivo)";

        if (eligibleDevices.length > 0) {
            const amountVal = parseFloat(amount);
            const capableDevices = eligibleDevices.filter(d => parseFloat(d.balance || 0) >= amountVal);

            if (capableDevices.length > 0) {
                capableDevices.sort((a, b) => parseFloat(b.balance) - parseFloat(a.balance));
                assignedTo = capableDevices[0].username;
                assignedName = capableDevices[0].name || assignedTo;
            }
        }

        const result = await dbRun(
            `INSERT INTO transfers (number, amount, status, assigned_to, owner_account) VALUES (?, ?, 'PENDENTE', ?, ?)`,
            [number, amount, assignedTo, ownerAccount]
        );

        res.status(201).json({
            id: result.lastID,
            message: 'Pedido agendado.',
            assigned_to: assignedTo,
            assigned_name: assignedName,
            number,
            amount
        });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao salvar pedido.' });
    }
});

// 2. App Endpoint: Device Login
app.post('/api/device/login', async (req, res) => {
    const { username, password, account } = req.body;
    if (!username || !password) return res.status(400).json({ error: 'Usuário e senha são obrigatórios.' });

    try {
        const device = await dbGet(`SELECT * FROM devices WHERE username = ? AND password = ?`, [username, password]);
        if (device) {
            await dbRun(
                `UPDATE devices SET account = ?, last_seen = CURRENT_TIMESTAMP WHERE username = ?`,
                [account || device.account, username]
            );
            res.json({ success: true, message: 'Login efetuado com sucesso', name: device.name });
        } else {
            res.status(401).json({ success: false, error: 'Credenciais inválidas' });
        }
    } catch (err) {
        res.status(500).json({ error: 'Erro interno no login' });
    }
});

// 2b. App Endpoint: Device Registration
app.post('/api/device/register', async (req, res) => {
    const { username, password, name, account } = req.body;
    if (!username || !password || !account) {
        return res.status(400).json({ error: 'ID, Senha e Conta são obrigatórios.' });
    }

    try {
        const existing = await dbGet(`SELECT username FROM devices WHERE username = ?`, [username]);
        if (existing) {
            return res.status(400).json({ error: 'Este ID de Aparelho já existe.' });
        }

        await dbRun(
            `INSERT INTO devices (username, password, name, account, balance, paused) VALUES (?, ?, ?, ?, '0 MB', 0)`,
            [username, password, name || username, account]
        );
        res.status(201).json({ success: true, message: 'Aparelho registado com sucesso.' });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao registar aparelho.' });
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
        // Find device's account
        const device = await dbGet(`SELECT account FROM devices WHERE username = ?`, [username]);
        if (!device) return res.status(400).json({ error: 'Dispositivo não encontrado.' });

        // Atomic transaction: Find a job assigned specifically to this user or unassigned belonging to this account
        await dbRun("BEGIN EXCLUSIVE TRANSACTION");

        const pending = await dbGet(
            `SELECT * FROM transfers WHERE status = 'PENDENTE' AND (assigned_to = ? OR (assigned_to IS NULL AND (owner_account = ? OR owner_account IS NULL))) ORDER BY created_at ASC LIMIT 1`,
            [username, device.account]
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

// 5b. App Endpoint: Toggle device pause status
app.post('/api/device/pause', async (req, res) => {
    const { username, paused } = req.body;
    if (!username) return res.status(400).json({ error: 'ID do dispositivo é obrigatório.' });

    try {
        await dbRun(`UPDATE devices SET paused = ? WHERE username = ?`, [paused ? 1 : 0, username]);
        res.json({ success: true, message: `Dispositivo ${paused ? 'pausado' : 'retomado'}.` });
    } catch (err) {
        res.status(500).json({ error: 'Erro ao alternar pausa.' });
    }
});

// 6. Dashboard Endpoint: View all devices in an account
app.get('/api/devices', async (req, res) => {
    const { account } = req.query;
    try {
        let query = `SELECT username, name, balance, paused, battery, last_seen FROM devices`;
        let params = [];
        if (account) {
            query += ` WHERE account = ?`;
            params.push(account);
        }
        const devices = await dbAll(query, params);
        res.json({ devices });
    } catch (err) {
        res.status(500).json({ error: 'Erro ao buscar dispositivos.' });
    }
});

// --- BACKGROUND WORKERS ---

// Unlock stalled jobs every 1 minute
// 1. Unlock stalled jobs (processing for too long)
// 2. Re-assign jobs from offline devices
setInterval(async () => {
    try {
        const twoMinutesAgo = new Date(Date.now() - 2 * 60 * 1000).toISOString().replace("T", " ").replace("Z", "");
        const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000).toISOString().replace("T", " ").replace("Z", "");

        // A. Stuck in PROCESSANDO
        const stalledJobs = await dbAll(`SELECT id FROM transfers WHERE status = 'PROCESSANDO' AND locked_at < ?`, [twoMinutesAgo]);
        for (let job of stalledJobs) {
            await dbRun(`UPDATE transfers SET status = 'PENDENTE', locked_by = NULL, locked_at = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?`, [job.id]);
        }

        // B. Assigned to offline devices (last_seen > 5 min ago)
        // Find devices that are effectively offline
        const offlineDevices = await dbAll(`SELECT username FROM devices WHERE last_seen < ?`, [fiveMinutesAgo]);
        if (offlineDevices.length > 0) {
            const usernames = offlineDevices.map(d => d.username);
            const placeholders = usernames.map(() => '?').join(',');

            // Unassign jobs from these devices so someone else can pick them up
            await dbRun(`
                UPDATE transfers 
                SET assigned_to = NULL, updated_at = CURRENT_TIMESTAMP 
                WHERE status = 'PENDENTE' AND assigned_to IN (${placeholders})
            `, usernames);
        }

    } catch (err) {
        console.error("[Auto-Unlock] Erro:", err.message);
    }
}, 60000); // 60 seconds

// Start Server
app.listen(PORT, () => {
    console.log(`Servidor USSD-Backend rodando na porta ${PORT}`);
});
