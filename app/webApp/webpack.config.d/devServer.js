config.devServer = config.devServer || {};
config.devServer.port = Number(process.env.PORT) || 8081;
config.devServer.open = false;
