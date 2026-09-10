export interface Account {
  accessToken: string;
  expiresAt: string;
  userId: string;
  email: string;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
  ) {
    super(code.replaceAll("_", " ").toLowerCase());
  }
}
export async function api<T>(
  path: string,
  account: Account | null,
  body?: unknown,
  method = body === undefined ? "GET" : "POST",
): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    method,
    headers: {
      ...(account ? { Authorization: `Bearer ${account.accessToken}` } : {}),
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) {
    const error = (await response
      .json()
      .catch(() => ({ code: `HTTP_${response.status}` }))) as { code?: string };
    throw new ApiError(
      response.status,
      error.code ?? `HTTP_${response.status}`,
    );
  }
  return response.status === 204
    ? (undefined as T)
    : (response.json() as Promise<T>);
}
