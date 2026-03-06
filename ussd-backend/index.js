const express = require('express');
const cors = require('cors');
const sqlite3 = require('sqlite3').verbose();
const path = require('path');
require('dotenv').config();

const app = express();
app.use(cors());
app.use(express.json());

// Global unhandled rejection logger for production stability
process.on('unhandledRejection', (reason, promise) => {
    console.error('[CRITICAL] Unhandled Rejection:', reason);
});

const PORT = process.env.PORT || 3000;

// Socket.io Setup
const httpServer = require('http').createServer(app);
const io = require('socket.io')(httpServer, {
    cors: { origin: "*", methods: ["GET", "POST"] }
});

io.on('connection', (socket) => {
    console.log(`[Socket] Novo ID: ${socket.id}`);

    socket.on('register_device', (data) => {
        if (data && data.username) {
            const userRoom = data.username.toLowerCase().trim();
            socket.join(userRoom);
            console.log(`[Socket] Dispositivo ${userRoom} registrado para PUSH direto.`);
        }
        if (data && data.account) {
            const accRoom = `acc_${data.account.toLowerCase().trim()}`;
            socket.join(accRoom);
            console.log(`[Socket] Dispositivo entrou na sala da conta: ${accRoom}`);
        }
    });

    socket.on('disconnect', () => {
        console.log('[Socket] Desconectado');
    });
});

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
                INSERT OR IGNORE INTO accounts (name, password)
                SELECT DISTINCT account, password FROM devices 
                WHERE account IS NOT NULL AND password IS NOT NULL
                AND LOWER(account) NOT IN (SELECT LOWER(name) FROM accounts)
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

    // Audit Logs Table
    db.run(`
        CREATE TABLE IF NOT EXISTS transfer_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            transfer_id INTEGER,
            username TEXT,
            status TEXT,
            message TEXT,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    `);

    // One-time cleanup for existing data (Trim whitespace)
    db.run(`UPDATE devices SET account = TRIM(account), username = TRIM(username) WHERE account IS NOT NULL`);
});

// --- API ROUTES ---

// 1. Bot Endpoint: Add new request to queue
app.post('/api/transfer', async (req, res) => {
    let { number, amount, account, targetDevice } = req.body;
    account = account?.trim()?.toLowerCase();
    targetDevice = targetDevice?.trim()?.toLowerCase();

    if (!number || !amount || !account) {
        return res.status(400).json({ error: 'Número, Quantidade e Conta são obrigatórios.' });
    }

    try {
        // Case-Insensitive account lookup
        const accRow = await dbGet(`SELECT id FROM accounts WHERE LOWER(name) = LOWER(?)`, [account]);
        if (!accRow) {
            console.log(`[Job Fail] Account not found: ${account}`);
            return res.status(404).json({ error: 'Conta não encontrada.' });
        }

        const ownerAccountId = accRow.id;

        let assignedTo = null;
        let assignedName = "Fila Geral (Aguardando Dispositivo)";

        if (targetDevice && targetDevice !== "auto") {
            const specificDev = await dbGet(`SELECT name FROM devices WHERE username = ? AND account_id = ?`, [targetDevice, ownerAccountId]);
            if (specificDev) {
                assignedTo = targetDevice;
                assignedTo = targetDevice.toLowerCase(); // Normalize targetDevice
                assignedName = specificDev.name || targetDevice;
            } else {
                return res.status(404).json({ error: 'Dispositivo alvo não encontrado na conta.' });
            }
        }

        const normalizedTarget = targetDevice ? targetDevice.toLowerCase() : 'auto';

        const result = await dbRun(`
            INSERT INTO transfers (number, amount, owner_account, assigned_to, status)
            VALUES (?, ?, ?, ?, 'PENDENTE')
        `, [number, amount, account, normalizedTarget === 'auto' ? null : assignedTo]);

        console.log(`[New Job] ${amount} MB to ${number} (Account: ${account}, Target: ${normalizedTarget})`);

        // --- REAL-TIME PUSH ---
        const jobPayload = {
            id: result.lastID,
            number,
            amount,
            account
        };

        if (assignedTo) {
            const room = assignedTo.toLowerCase().trim();
            io.to(room).emit('new_job', jobPayload);
            console.log(`[Push] Evento DIRETO enviado para sala: ${room}`);
        } else {
            const room = `acc_${account.toLowerCase().trim()}`;
            io.to(room).emit('new_job', jobPayload);
            console.log(`[Push] Evento GERAL enviado para sala da conta: ${room}`);
        }

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
    username = username?.trim()?.toLowerCase();
    password = password?.trim();
    account = account?.trim()?.toLowerCase();
    name = name?.trim();

    if (!account || !password || !username) {
        return res.status(400).json({ error: 'Conta, senha e ID do aparelho são obrigatórios.' });
    }

    try {
        console.log(`[Login Attempt] Device: ${username}, Account: ${account}`);

        // 1. Authenticate Account (Case-Insensitive name)
        const accRow = await dbGet(`SELECT id FROM accounts WHERE LOWER(name) = LOWER(?) AND password = ?`, [account, password]);

        if (accRow) {
            console.log(`[Login Success] Account Auth OK. Syncing device: ${username}`);
            const accountId = accRow.id;

            // 2. Manage Device Association (Universally compatible Upsert)
            await dbRun(
                `INSERT OR IGNORE INTO devices (username, account_id, name, balance, paused) 
                 VALUES (?, ?, ?, '0 MB', 0)`,
                [username, accountId, name || username]
            );
            await dbRun(
                `UPDATE devices SET account_id = ?, name = ? WHERE username = ?`,
                [accountId, name || username, username]
            );

            res.json({ success: true, message: 'Login efetuado com sucesso na conta ' + account });
        } else {
            const accExists = await dbGet(`SELECT name, password FROM accounts WHERE LOWER(name) = LOWER(?)`, [account]);
            if (accExists) {
                console.log(`[Login Fail] Account found: ${account}, but password mismatch.`);
                res.status(401).json({ success: false, error: 'Senha da conta incorreta' });
            } else {
                console.log(`[Login Fail] Account not found: "${account}"`);
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

    username = username?.trim()?.toLowerCase();
    password = password?.trim();
    account = account?.trim()?.toLowerCase();
    name = name?.trim();

    if (!username || !password || !account) {
        return res.status(400).json({ error: 'ID, Senha e Conta são obrigatórios.' });
    }

    try {
        const accExists = await dbGet(`SELECT id FROM accounts WHERE LOWER(name) = LOWER(?)`, [account]);
        if (accExists) {
            return res.status(400).json({ error: 'Esta conta já existe. Use o Login.' });
        }

        // Create the account
        const accResult = await dbRun(
            `INSERT INTO accounts (name, password) VALUES (?, ?)`,
            [account, password]
        );
        const accountId = accResult.lastID;

        // Associate the first device (Universally compatible Upsert)
        await dbRun(
            `INSERT OR IGNORE INTO devices (username, account_id, name, balance, paused) 
             VALUES (?, ?, ?, '0 MB', 0)`,
            [username, accountId, name || username]
        );
        await dbRun(
            `UPDATE devices SET account_id = ?, name = ? WHERE username = ?`,
            [accountId, name || username, username]
        );

        res.status(201).json({ success: true, message: 'Conta criada e aparelho associado.' });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao registar conta.' });
    }
});

// 3. App Endpoint: Status Heartbeat
app.post('/api/device/status', async (req, res) => {
    const { username, battery, balance, paused } = req.body;
    if (!username) return res.status(400).json({ error: 'Username missing' });

    console.log(`[Heartbeat] ${username} | Bat: ${battery}% | Bal: ${balance} | Paused: ${paused}`);

    try {
        await dbRun(
            `UPDATE devices SET balance = ?, paused = ?, battery = ?, last_seen = CURRENT_TIMESTAMP WHERE LOWER(username) = LOWER(?)`,
            [String(balance), paused ? 1 : 0, battery !== undefined ? battery : 100, username]
        );
        res.json({ success: true });
    } catch (err) {
        console.error(`[Heartbeat Error] Device ${username}:`, err);
        res.status(500).json({ error: 'Erro ao atualizar status do dispositivo.' });
    }
});

// 3b. Monitor Endpoint: Health Check
app.get('/api/health', (req, res) => {
    db.get("SELECT 1", [], (err) => {
        if (err) return res.status(500).json({ status: 'DOWN', error: err.message });
        res.json({
            status: 'OK',
            time: new Date().toISOString(),
            database: 'Connected',
            uptime: Math.floor(process.uptime()) + 's'
        });
    });
});

// 4. App Endpoint: Fetch next pending request (Concurreny Safe)
app.post('/api/transfer/pending', async (req, res) => {
    let { username, jobId } = req.body;
    username = username?.trim()?.toLowerCase();

    if (!username) return res.status(400).json({ error: 'Username é obrigatório.' });

    try {
        const device = await dbGet(
            `SELECT d.*, a.name as account_name FROM devices d 
             JOIN accounts a ON d.account_id = a.id 
             WHERE d.username = ?`,
            [username]
        );

        if (!device) return res.status(400).json({ error: 'Dispositivo não encontrado ou não associado a uma conta.' });

        // Atomic transaction: Find a job
        await dbRun("BEGIN EXCLUSIVE TRANSACTION");

        let pending = null;

        // --- Targeted Claim (WebSocket Flow) ---
        if (jobId) {
            pending = await dbGet(
                `SELECT * FROM transfers WHERE id = ? AND status = 'PENDENTE' AND (assigned_to IS NULL OR LOWER(assigned_to) = LOWER(?))`,
                [jobId, username]
            );
            if (pending) {
                console.log(`[Claim] Device ${username} is claiming specific job ${jobId}`);
            }
        }

        // --- Standard Dispatch (Polling Flow) ---
        if (!pending) {
            // First, check if there's a job explicitly assigned to this device
            pending = await dbGet(
                `SELECT * FROM transfers WHERE status = 'PENDENTE' AND LOWER(assigned_to) = LOWER(?) ORDER BY created_at ASC LIMIT 1`,
                [username]
            );
        }

        // If no explicit job, look for unassigned jobs for this account, but ONLY if the device is not paused
        if (!pending && device.paused === 0) {
            // Find the oldest unassigned job
            const unassigned = await dbAll(
                `SELECT * FROM transfers WHERE status = 'PENDENTE' AND assigned_to IS NULL AND (LOWER(owner_account) = LOWER(?) OR owner_account IS NULL) ORDER BY created_at ASC`,
                [device.account_name]
            );

            if (unassigned.length > 0) {
                console.log(`[Poll] Device ${username}: Found ${unassigned.length} unassigned jobs. Checking balances...`);
            }

            // Find the first unassigned job this device can afford
            const deviceBalance = parseFloat(device.balance || 0);
            pending = unassigned.find(job => {
                const jobAmount = parseFloat(job.amount || 0);
                if (jobAmount <= deviceBalance) return true;
                console.log(`[Poll Skip] Job ${job.id} needs ${jobAmount}, device ${username} has ${deviceBalance}`);
                return false;
            });

            if (!pending && unassigned.length > 0) {
                await dbRun("COMMIT");
                console.log(`[Poll] ${username} -> All ${unassigned.length} jobs rejected due to balance (${deviceBalance} MB).`);
                return res.json({ message: 'Nenhum pedido compatível com seu saldo.', job: null });
            }

            if (!pending) {
                await dbRun("COMMIT");
                return res.json({ message: 'Sem pedidos não atribuídos.', job: null });
            }
        } else if (!pending) {
            // Device is paused and has no explicitly assigned jobs
            await dbRun("COMMIT");
            if (device.paused !== 0) {
                console.log(`[Poll] ${username} is PAUSED. Skipping unassigned jobs.`);
            }
            return res.json({ message: 'Dispositivo pausado e sem pedidos diretos.', job: null });
        }

        if (!pending) {
            await dbRun("COMMIT");
            // Silently return for non-jobs to avoid log spam, or keep it for deep debugging
            // console.log(`[Poll] ${username} -> No work found.`);
            return res.json({ message: 'Nenhum pedido pendente.', job: null });
        }

        console.log(`[Job Dispatched] ID ${pending.id} assigned to ${username}`);

        // Lock it
        await dbRun(
            `UPDATE transfers SET status = 'PROCESSANDO', locked_by = ?, locked_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?`,
            [username, pending.id]
        );

        await dbRun("COMMIT");

        res.json({ job: pending });
    } catch (err) {
        await dbRun("ROLLBACK").catch(() => { });
        console.error("Lock error for user " + username + ":", err);
        res.status(500).json({ error: 'Erro ao buscar pedidos.' });
    }
});

// 5. App Endpoint: Report status (SUCCESS/FAILURE)
app.post('/api/transfer/update', async (req, res) => {
    let { id, status, username } = req.body;
    username = username?.trim()?.toLowerCase();
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

        // Audit log
        await dbRun(
            `INSERT INTO transfer_logs (transfer_id, username, status, message) VALUES (?, ?, ?, ?)`,
            [id, username, status, `Update reported by client`]
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

// 5c. App Endpoint: Retry failed jobs
app.post('/api/transfer/retry_failed', async (req, res) => {
    const { account } = req.body;
    if (!account) return res.status(400).json({ error: 'Conta é obrigatória.' });

    try {
        await dbRun(
            `UPDATE transfers SET status = 'PENDENTE', locked_by = NULL, locked_at = NULL, updated_at = CURRENT_TIMESTAMP WHERE status = 'FALHA' AND owner_account = ?`,
            [account]
        );
        res.json({ success: true, message: 'Pedidos falhados foram devolvidos à fila.' });
    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Erro ao reprocessar pedidos.' });
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

// Aggressive job unlocking and re-assignment
// 1. Unlock jobs stuck in 'PROCESSANDO' (1 minute timeout)
// 2. Unassign 'PENDENTE' jobs from offline devices (60 seconds timeout)
setInterval(async () => {
    try {
        const now = Date.now();
        const oneMinuteAgo = new Date(now - 60 * 1000).toISOString().replace("T", " ").replace("Z", "");
        const sixtySecondsAgo = new Date(now - 60 * 1000).toISOString().replace("T", " ").replace("Z", "");

        // A. Stuck in PROCESSANDO
        const stalledJobs = await dbAll(`SELECT id, locked_by FROM transfers WHERE status = 'PROCESSANDO' AND locked_at < ?`, [oneMinuteAgo]);
        for (let job of stalledJobs) {
            console.log(`[Auto-Unlock] Unlocking job ${job.id} from ${job.locked_by} due to timeout.`);
            await dbRun(`UPDATE transfers SET status = 'PENDENTE', locked_by = NULL, locked_at = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?`, [job.id]);
            await dbRun(`INSERT INTO transfer_logs (transfer_id, username, status, message) VALUES (?, ?, 'PENDENTE', ?)`, [job.id, job.locked_by, 'Auto-unlocked due to timeout']);
        }

        // B. Assigned to offline devices
        // Find devices that are effectively offline (last_seen > 1 min ago)
        const offlineDevices = await dbAll(`SELECT username FROM devices WHERE last_seen < ?`, [sixtySecondsAgo]);
        if (offlineDevices.length > 0) {
            const usernames = offlineDevices.map(d => d.username);
            const placeholders = usernames.map(() => '?').join(',');

            // Unassign jobs from these devices so someone else can pick them up
            const affectedJobs = await dbAll(`SELECT id FROM transfers WHERE status = 'PENDENTE' AND assigned_to IN (${placeholders})`, usernames);
            if (affectedJobs.length > 0) {
                console.log(`[Auto-Reassign] Unassigning ${affectedJobs.length} jobs from offline devices: ${usernames.join(',')}`);
                await dbRun(`
                    UPDATE transfers 
                    SET assigned_to = NULL, updated_at = CURRENT_TIMESTAMP 
                    WHERE status = 'PENDENTE' AND assigned_to IN (${placeholders})
                `, usernames);
            }
        }

    } catch (err) {
        console.error("[Auto-Unlock] Erro Crítico:", err);
    }
}, 30000); // Run every 30 seconds

// Start Server
httpServer.listen(PORT, () => {
    console.log(`Servidor USSD-Backend (com WebSockets) rodando na porta ${PORT}`);
});
