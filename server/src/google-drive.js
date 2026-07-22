import { AppError, readJson } from "./errors.js";

const DRIVE_API = "https://www.googleapis.com/drive/v3";
const DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3";
const FILE_FIELDS = "id,name,modifiedTime,createdTime,size,appProperties";

function escapeQuery(value) {
  return String(value).replace(/\\/g, "\\\\").replace(/'/g, "\\'");
}

async function driveRequest(path, accessToken, {
  method = "GET",
  body = undefined,
  headers = {},
  upload = false,
  fetchImpl = fetch,
  expectJson = true,
} = {}) {
  let response;
  try {
    response = await fetchImpl(`${upload ? DRIVE_UPLOAD_API : DRIVE_API}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        ...headers,
      },
      body,
    });
  } catch (error) {
    throw new AppError("Google Drive временно недоступен", {
      code: "DRIVE_UNAVAILABLE",
      status: 502,
      cause: error,
    });
  }

  if (response.ok) {
    if (!expectJson || response.status === 204) return null;
    return readJson(response, "Google Drive вернул некорректный ответ");
  }

  let details = null;
  try {
    details = await response.json();
  } catch (_) {
    details = null;
  }
  if (response.status === 401 || response.status === 403) {
    throw new AppError("Google больше не разрешает доступ к Drive", {
      code: "GOOGLE_REAUTH_REQUIRED",
      status: 401,
      details: details?.error?.status || null,
    });
  }
  throw new AppError("Google Drive отклонил запрос", {
    code: "DRIVE_REQUEST_FAILED",
    status: response.status >= 500 ? 502 : 409,
    details: details?.error?.message || null,
  });
}

export async function findBackup(accessToken, backupFileName, fetchImpl = fetch) {
  const parameters = new URLSearchParams({
    spaces: "appDataFolder",
    q: `name='${escapeQuery(backupFileName)}' and trashed=false`,
    orderBy: "modifiedTime desc",
    pageSize: "10",
    fields: `files(${FILE_FIELDS})`,
  });
  const result = await driveRequest(`/files?${parameters}`, accessToken, { fetchImpl });
  return result?.files?.[0] || null;
}

export async function downloadBackup(accessToken, file, fetchImpl = fetch) {
  if (!file) return null;
  let response;
  try {
    response = await fetchImpl(`${DRIVE_API}/files/${encodeURIComponent(file.id)}?alt=media`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
  } catch (error) {
    throw new AppError("Google Drive временно недоступен", {
      code: "DRIVE_UNAVAILABLE",
      status: 502,
      cause: error,
    });
  }
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      throw new AppError("Google больше не разрешает доступ к Drive", {
        code: "GOOGLE_REAUTH_REQUIRED",
        status: 401,
      });
    }
    throw new AppError("Не удалось скачать облачную копию", {
      code: "DRIVE_DOWNLOAD_FAILED",
      status: 502,
    });
  }
  try {
    return JSON.parse(await response.text());
  } catch (error) {
    throw new AppError("Облачная копия содержит повреждённый JSON", {
      code: "INVALID_BACKUP_JSON",
      status: 409,
      cause: error,
    });
  }
}

export async function uploadBackup(
  accessToken,
  backupFileName,
  payload,
  file,
  fetchImpl = fetch,
) {
  let fileId = file?.id || null;
  if (!fileId) {
    const metadata = await driveRequest(
      `/files?${new URLSearchParams({ fields: FILE_FIELDS })}`,
      accessToken,
      {
        method: "POST",
        headers: { "Content-Type": "application/json; charset=utf-8" },
        body: JSON.stringify({
          name: backupFileName,
          parents: ["appDataFolder"],
          appProperties: { app: "las-salary", schemaVersion: "3" },
        }),
        fetchImpl,
      },
    );
    fileId = metadata?.id;
    if (!fileId) {
      throw new AppError("Google Drive не создал файл резервной копии", {
        code: "DRIVE_FILE_CREATE_FAILED",
        status: 502,
      });
    }
  }

  return driveRequest(
    `/files/${encodeURIComponent(fileId)}?${new URLSearchParams({
      uploadType: "media",
      fields: FILE_FIELDS,
    })}`,
    accessToken,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json; charset=utf-8" },
      body: JSON.stringify(payload),
      upload: true,
      fetchImpl,
    },
  );
}

export async function renameBackup(accessToken, file, newName, fetchImpl = fetch) {
  if (!file?.id) return null;
  return driveRequest(
    `/files/${encodeURIComponent(file.id)}?${new URLSearchParams({ fields: FILE_FIELDS })}`,
    accessToken,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json; charset=utf-8" },
      body: JSON.stringify({ name: newName }),
      fetchImpl,
    },
  );
}

export async function listManagedBackups(accessToken, backupFileName, fetchImpl = fetch) {
  const files = [];
  let pageToken = "";
  do {
    const parameters = new URLSearchParams({
      spaces: "appDataFolder",
      q: "trashed=false",
      pageSize: "1000",
      fields: "nextPageToken,files(id,name,appProperties)",
    });
    if (pageToken) parameters.set("pageToken", pageToken);
    const result = await driveRequest(`/files?${parameters}`, accessToken, { fetchImpl });
    for (const file of result?.files || []) {
      const managedName = file.name === backupFileName
        || file.name?.startsWith(`${backupFileName}.corrupt-`);
      if (managedName || file.appProperties?.app === "las-salary") files.push(file);
    }
    pageToken = result?.nextPageToken || "";
  } while (pageToken);
  return files;
}

export async function deleteDriveFile(accessToken, fileId, fetchImpl = fetch) {
  await driveRequest(`/files/${encodeURIComponent(fileId)}`, accessToken, {
    method: "DELETE",
    fetchImpl,
    expectJson: false,
  });
}
