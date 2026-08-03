import type { TimeDisplay } from "./preferences";

export function formatTimestamp(
  value: string | Date | null | undefined,
  timeDisplay: TimeDisplay = "local",
) {
  if (!value) return "—";

  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return "—";

  const formatted = new Intl.DateTimeFormat(undefined, {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    second: "numeric",
    timeZone: timeDisplay === "utc" ? "UTC" : undefined,
  }).format(date);

  return timeDisplay === "utc" ? `${formatted} UTC` : formatted;
}
