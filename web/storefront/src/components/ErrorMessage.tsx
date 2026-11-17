import { ApiError, messageOf } from '../api/client';

/** An error, with the reference support needs to find the request in the logs. */
export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null;
  const reference = error instanceof ApiError ? error.requestId : undefined;
  return (
    <p className="alert alert-error" role="alert">
      {messageOf(error)}
      {reference && <span className="reference"> Reference: {reference}</span>}
    </p>
  );
}
