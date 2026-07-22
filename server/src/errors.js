export class AppError extends Error {
  constructor(message, { code = "APP_ERROR", status = 500, cause = null, details = null } = {}) {
    super(message, { cause });
    this.name = "AppError";
    this.code = code;
    this.status = status;
    this.details = details;
  }
}

export function publicError(error) {
  if (error instanceof AppError) return error;
  return new AppError("Внутренняя ошибка сервера", { cause: error });
}

export async function readJson(response, fallbackMessage) {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new AppError(fallbackMessage, {
      code: "UPSTREAM_INVALID_JSON",
      status: 502,
      cause: error,
    });
  }
}
