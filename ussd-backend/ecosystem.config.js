module.exports = {
    apps: [{
        name: "ussd-backend",
        script: "./index.js",
        instances: 1,
        exec_mode: "fork",
        watch: false,
        env: {
            NODE_ENV: "production",
            PORT: 3003
        }
    }]
}
