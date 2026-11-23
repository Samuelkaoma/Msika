// A server-sent events reader built on fetch (card M-51). The browser's EventSource cannot send the sign-in token in
// a header, and putting it in the URL would leave it in server logs, so this reads the stream itself. When the line
// drops (the server restarted, the phone network blinked) it waits and connects again, without reloading the page,
// and tells the page so it can reload the figures it may have missed.

import { currentSession } from "../api/client";

export type LiveState = "connecting" | "live" | "reconnecting" | "stopped";

export type SseMessage = { event: string; data: string; id?: string };

/** Splits a chunk of an event stream into messages; returns what is left over for the next chunk. */
export function parseSse(buffer: string): { messages: SseMessage[]; rest: string } {
  const messages: SseMessage[] = [];
  const blocks = buffer.replace(/\r\n/g, "\n").split("\n\n");
  const rest = blocks.pop() ?? "";
  for (const block of blocks) {
    let event = "message";
    let id: string | undefined;
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith(":")) continue; // a comment, such as a keep-alive
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
      if (field === "event") event = value;
      else if (field === "data") data.push(value);
      else if (field === "id") id = value;
    }
    if (data.length > 0) messages.push({ event, data: data.join("\n"), id });
  }
  return { messages, rest };
}

export type LiveOptions = {
  onMessage: (m: SseMessage) => void;
  /** Called after every reconnection (not the first connection): reload whatever may have been missed. */
  onReconnect: () => void;
  onState: (s: LiveState) => void;
  /** Waits between attempts, in milliseconds; the last is repeated. */
  backoffMs?: number[];
};

export function listen(url: string, options: LiveOptions): () => void {
  const backoff = options.backoffMs ?? [1_000, 2_000, 5_000, 10_000];
  let stopped = false;
  let attempt = 0;
  let connectedBefore = false;
  let controller: AbortController | null = null;

  const run = async () => {
    while (!stopped) {
      controller = new AbortController();
      options.onState(connectedBefore ? "reconnecting" : "connecting");
      try {
        const s = currentSession();
        const response = await fetch(url, {
          headers: { Accept: "text/event-stream", ...(s ? { Authorization: `Bearer ${s.token}` } : {}) },
          signal: controller.signal,
        });
        if (response.status === 401 || response.status === 403) {
          options.onState("stopped");
          return;
        }
        if (!response.ok || !response.body) throw new Error(`stream answered ${response.status}`);
        options.onState("live");
        attempt = 0;
        if (connectedBefore) options.onReconnect();
        connectedBefore = true;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const parsed = parseSse(buffer);
          buffer = parsed.rest;
          parsed.messages.forEach(options.onMessage);
        }
      } catch {
        // dropped or refused: wait and try again below
      }
      if (stopped) break;
      options.onState("reconnecting");
      await new Promise((r) => setTimeout(r, backoff[Math.min(attempt, backoff.length - 1)]));
      attempt++;
    }
  };
  void run();
  return () => {
    stopped = true;
    controller?.abort();
    options.onState("stopped");
  };
}
