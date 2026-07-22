import express from "express";
import helmet from "helmet";
import { AppError, publicError } from "./errors.js";

function bearerToken(request) {
  const header = request.get("authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match?.[1] || "";
}

function rateLimiter({ limit = 30, windowMs = 10 * 60 * 1000 } = {}) {
  const buckets = new Map();
  let requestsSinceCleanup = 0;
  return (request, _response, next) => {
    const key = request.ip || "unknown";
    const now = Date.now();

    requestsSinceCleanup += 1;
    if (requestsSinceCleanup >= 250) {
      requestsSinceCleanup = 0;
      for (const [bucketKey, bucket] of buckets) {
        if (bucket.resetAt <= now) buckets.delete(bucketKey);
      }
    }

    const current = buckets.get(key);
    if (!current || current.resetAt <= now) {
      buckets.set(key, { count: 1, resetAt: now + windowMs });
      return next();
    }
    current.count += 1;
    if (current.count > limit) {
      return next(new AppError("Слишком много запросов. Повторите немного позже.", {
        code: "RATE_LIMITED",
        status: 429,
      }));
    }
    next();
  };
}

export function createApp({ config, store, authService, driveService }) {
  const app = express();
  app.set("trust proxy", true);
  app.disable("x-powered-by");
  app.use(helmet({
    contentSecurityPolicy: false,
    crossOriginOpenerPolicy: false,
    crossOriginResourcePolicy: { policy: "cross-origin" },
  }));
  app.use(express.json({ limit: "2mb", type: "application/json" }));
  app.use("/api", (_request, response, next) => {
    response.set("Cache-Control", "no-store");
    next();
  });

  app.use((request, response, next) => {
    const origin = request.get("origin");
    if (origin && config.allowedOrigins.has(origin)) {
      response.set("Access-Control-Allow-Origin", origin);
      response.set("Vary", "Origin");
      response.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With");
      response.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    }
    if (request.method === "OPTIONS") {
      if (!origin || !config.allowedOrigins.has(origin)) return response.sendStatus(403);
      return response.sendStatus(204);
    }
    next();
  });

  const requireAllowedOrigin = (request, _response, next) => {
    const origin = request.get("origin");
    if (!origin || !config.allowedOrigins.has(origin)) {
      return next(new AppError("Этот сайт не разрешён для API", {
        code: "ORIGIN_NOT_ALLOWED",
        status: 403,
      }));
    }
    next();
  };

  const requireRequestedWith = (request, _response, next) => {
    if ((request.get("x-requested-with") || "").toLowerCase() !== "xmlhttprequest") {
      return next(new AppError("Некорректный запрос авторизации", {
        code: "AUTH_REQUEST_INVALID",
        status: 403,
      }));
    }
    next();
  };

  const requireSession = async (request, _response, next) => {
    try {
      const token = bearerToken(request);
      if (!token) throw new AppError("Подключите Google", { code: "AUTH_REQUIRED", status: 401 });
      const session = await store.readSession(token);
      if (!session) throw new AppError("Сессия истекла. Подключите Google повторно.", { code: "AUTH_REQUIRED", status: 401 });
      request.cloudSession = session;
      request.cloudSessionToken = token;
      next();
    } catch (error) {
      next(error);
    }
  };

  app.get("/healthz", (_request, response) => response.json({ ok: true }));

  app.post(
    "/api/auth/code",
    rateLimiter({ limit: 20 }),
    requireAllowedOrigin,
    requireRequestedWith,
    async (request, response, next) => {
      try {
        const code = request.body?.code;
        if (typeof code !== "string" || !code.trim() || code.length > 8192) {
          throw new AppError("Google не вернул корректный код авторизации", {
            code: "OAUTH_CODE_INVALID",
            status: 400,
          });
        }
        const result = await authService.exchangeCode({
          code: code.trim(),
          callbackUri: request.get("origin"),
        });
        response.json(result);
      } catch (error) {
        next(error);
      }
    },
  );

  app.get("/api/auth/session", requireAllowedOrigin, requireSession, (request, response) => {
    response.json({ account: request.cloudSession.account });
  });

  app.post("/api/auth/logout", requireAllowedOrigin, requireSession, async (request, response, next) => {
    try {
      await store.deleteSession(request.cloudSessionToken);
      response.sendStatus(204);
    } catch (error) {
      next(error);
    }
  });

  app.delete("/api/auth/access", requireAllowedOrigin, requireSession, async (request, response, next) => {
    try {
      await authService.revoke(request.cloudSession.userId);
      response.sendStatus(204);
    } catch (error) {
      next(error);
    }
  });

  app.delete("/api/cloud-data", requireAllowedOrigin, requireSession, async (request, response, next) => {
    try {
      const result = await driveService.deleteCloudData(request.cloudSession.userId);
      response.json(result);
    } catch (error) {
      next(error);
    }
  });

  app.post("/api/sync", requireAllowedOrigin, requireSession, async (request, response, next) => {
    try {
      if (!request.body?.payload) {
        throw new AppError("Нет данных для синхронизации", { code: "PAYLOAD_MISSING", status: 400 });
      }
      const result = await driveService.synchronize(request.cloudSession.userId, request.body.payload);
      response.json(result);
    } catch (error) {
      next(error);
    }
  });

  app.use((_request, _response, next) => {
    next(new AppError("Маршрут не найден", { code: "NOT_FOUND", status: 404 }));
  });

  app.use((error, _request, response, _next) => {
    const failure = publicError(error);
    if (failure.status >= 500) {
      console.error(JSON.stringify({
        severity: "ERROR",
        code: failure.code,
        message: error?.message || failure.message,
      }));
    }
    response.status(failure.status).json({
      error: {
        code: failure.code,
        message: failure.message,
        details: failure.details,
      },
    });
  });

  return app;
}
