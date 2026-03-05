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
        CREATE TABLE IF NOT EXISTS accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT UNIQUE,
            password TEXT NOT NULL
        )
    `);

    db.run(`
        CREATE TABLE IF NOT EXISTS devices (
            username TEXT PRIMARY KEY,
            account_id INTEGER,
            name TEXT,
            balance TEXT,
            paused BOOLEAN DEFAULT 0,
            battery INTEGER DEFAULT 100,
            last_seen DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(account_id) REFERENCES accounts(id)
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

    // Migration: Populate accounts from old devices data if columns exist
    db.all("PRAGMA table_info(devices)", (err, columns) => {
        if (err) return;
        const hasAccount = columns.find(c => c.name === 'account');
        const hasPassword = columns.find(c => c.name === 'password');

        if (hasAccount && hasPassword) {
            db.run(`
                INSERT INTO accounts (name, password)
                SELECT DISTINCT account, password FROM devices 
                WHERE account IS NOT NULL AND password IS NOT NULL
                AND account NOT IN (SELECT name FROM accounts)
            `, (err) => {
                if (!err) {
                    db.run(`
                        UPDATE devices 
                        SET account_id = (SELECT id FROM accounts WHERE accounts.name = devices.account)
                        WHERE account_id IS NULL AND account IS NOT NULL
                    `);
                }
            });
        }
    });

    // Insert a default admin device for immediate testing if it doesn't exist
    db.run(`INSERT OR IGNORE INTO accounts (name, password) VALUES ('admin_acc', 'admin123')`);

    // One-time cleanup for existing data (Trim whitespace)
    db.run(`UPDATE devices SET account = TRIM(account), username = TRIM(username) WHERE account IS NOT NULL`);
});

// --- API ROUTES ---

// 1. Bot Endpoint: Add new request to queue
app.post('/api/transfer', async (req, res) => {
    let { number, amount, account } = req.body;
    account = account?.trim();

    if (!number || !amount || !account) {
        return res.status(400).json({ error: 'Número, Quantidade e Conta são obrigatórios.' });
    }

    try {
        const accRow = await dbGet(`SELECT id FROM accounts WHERE name = ?`, [account]);
        if (!accRow) return res.status(404).json({ error: 'Conta não encontrada.' });

        const ownerAccountId = accRow.id;

        // --- INDUSTRIAL ROUTING LOGIC (Query Mestra) ---
        const activeThreshold = new Date(Date.now() - 2 * 60 * 1000).toISOString().replace("T", " ").replace("Z", "");

        // Find eligible devices in the SAME account, prioritize by balance
        const eligibleDevices = await dbAll(`
            SELECT username, name, balance FROM devices 
            WHERE account_id = ? AND paused = 0 AND last_seen > ?
            ORDER BY CAST(REPLACE(balance, ' MB', '') AS FLOAT) DESC
        `, [ownerAccountId, activeThreshold]);

        let assignedTo = null;
        let assignedName = "Fila Geral (Aguardando Dispositivo)";

        if (eligibleDevices.length > 0) {
            const amountVal = parseFloat(amount);
            // Check if any has enough balance
            const capable = eligibleDevices.find(d => parseFloat(d.balance || 0) >= amountVal);
            if (capable) {
                assignedTo = capable.username;
                assignedName = capable.name || assignedTo;
            }
        }

        const result = await dbRun(
            `INSERT INTO transfers (number, amount, status, assigned_to, owner_account) VALUES (?, ?, 'PENDENTE', ?, ?)`,
            [number, amount, assignedTo, account]
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
        res.status(500).json({ error: 'Erro ao agendar pedido.' });
    }
});

// 2. App Endpoint: Device Login (Account-Based)
app.post('/api/device/login', async (req, res) => {
    let { username, password, account, name } = req.body;

    // Trim and normalize
    username = username?.trim();
    password = password?.trim();
    account = account?.trim();
    name = name?.trim();

    if (!account || !password || !username) {
        return res.status(400).json({ error: 'Conta, senha e ID do aparelho são obrigatórios.' });
    }

    try {
        console.log(`[Login Attempt] Device: ${username}, Account: ${account}`);

        // 1. Authenticate Account
        const accRow = await dbGet(`SELECT id FROM accounts WHERE name = ? AND password = ?`, [account, password]);

        if (accRow) {
            console.log(`[Login Success] Account Auth OK. Syncing device: ${username}`);
            const accountId = accRow.id;

            // 2. Manage Device Association
            await dbRun(
                `INSERT INTO devices (username, account_id, name, balance, paused) 
                 VALUES (?, ?, ?, '0 MB', 0)
                 ON CONFLICT(username) DO UPDATE SET account_id = ?, name = EXCLUDED.name`,
                [username, accountId, name || username, accountId]
            );

            res.json({ success: true, message: 'Login efetuado com sucesso na conta ' + account });
        } else {
            console.log(`[Login Fail] Invalid credentials for account: ${account}`);
            const accExists = await dbGet(`SELECT id FROM accounts WHERE name = ?`, [account]);
            if (accExists) {
                res.status(401).json({ success: false, error: 'Senha da conta incorreta' });
            } else {
                res.status(404).json({ success: false, error: 'Conta não encontrada. Use "Criar Conta" primeiro.' });
            }
        }
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro interno no login' });
    }
});

// 2b. App Endpoint: Device Registration (Account Creation)
app.post('/api/device/register', async (req, res) => {
    let { username, password, name, account } = req.body;

    username = username?.trim();
    password = password?.trim();
    account = account?.trim();
    name = name?.trim();

    if (!username || !password || !account) {
        return res.status(400).json({ error: 'ID, Senha e Conta são obrigatórios.' });
    }

    try {
        const accExists = await dbGet(`SELECT id FROM accounts WHERE name = ?`, [account]);
        if (accExists) {
            return res.status(400).json({ error: 'Esta conta já existe. Use o Login.' });
        }

        // Create the account
        const accResult = await dbRun(
            `INSERT INTO accounts (name, password) VALUES (?, ?)`,
            [account, password]
        );
        const accountId = accResult.lastID;

        // Associate the first device
        await dbRun(
            `INSERT INTO devices (username, account_id, name, balance, paused) 
             VALUES (?, ?, ?, '0 MB', 0)`,
            [username, accountId, name || username]
        );

        res.status(201).json({ success: true, message: 'Conta criada e aparelho associado.' });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao registar conta.' });
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
        // Find device's account name
        const device = await dbGet(`
            SELECT a.name as account_name FROM devices d
            JOIN accounts a ON d.account_id = a.id
            WHERE d.username = ?
        `, [username]);

        if (!device) return res.status(400).json({ error: 'Dispositivo não encontrado ou não associado a uma conta.' });

        // Atomic transaction: Find a job assigned specifically to this user or unassigned belonging to this account
        await dbRun("BEGIN EXCLUSIVE TRANSACTION");

        const pending = await dbGet(
            `SELECT * FROM transfers WHERE status = 'PENDENTE' AND (assigned_to = ? OR (assigned_to IS NULL AND (owner_account = ? OR owner_account IS NULL))) ORDER BY created_at ASC LIMIT 1`,
            [username, device.account_name]
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
    const { account } = req.query; // Account name
    try {
        let query = `
            SELECT d.username, d.name, d.balance, d.paused, d.battery, d.last_seen 
            FROM devices d
            JOIN accounts a ON d.account_id = a.id
        `;
        let params = [];
        if (account) {
            query += ` WHERE a.name = ?`;
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
