import { api, ApiError } from "./api";
import type { Account } from "./api";
import { SequenceCrdt, editOperations, key } from "./crdt";
import type { Id, Operation, State } from "./crdt";
export type Role = "OWNER" | "EDITOR" | "VIEWER";
export interface Presence {
  connectionId: string;
  userId: string;
  actorId: string;
  cursor: { anchor: Id | null; focus: Id | null };
  updatedAt: number;
}
export interface View {
  text: string;
  status: string;
  role: Role;
  pending: number;
  watermark: number;
  users: Presence[];
  error: string;
  ready: boolean;
  presenceAvailable?: boolean;
}
interface Snapshot {
  watermark: number;
  state: State;
}
interface Ticket {
  ticket: string;
  actorId: string;
  nextSequence: number;
  maxLamport: number;
  socketPath: string;
}
export interface Entry {
  serverSequence: number;
  operation: Operation;
}
interface Page {
  operations: Entry[];
  throughSequence: number;
  documentSequence: number;
  hasMore: boolean;
  role: Role;
}
interface Message {
  protocolVersion: number;
  documentId: string;
  type: string;
  data: unknown;
}

/** ACK removes retry work. Only contiguous history advances the durable replay cursor. */
export function applyPage(
  replica: SequenceCrdt,
  watermark: number,
  page: Pick<Page, "operations" | "throughSequence">,
): number {
  for (const entry of page.operations) {
    if (entry.serverSequence <= watermark) continue;
    if (entry.serverSequence !== watermark + 1)
      throw Error("Replay gap; synchronization required");
    replica.apply(entry.operation);
    watermark = entry.serverSequence;
  }
  if (page.throughSequence !== watermark)
    throw Error("Invalid replay watermark");
  return watermark;
}
export function recover(
  snapshot: Snapshot,
  pending: Operation[],
): SequenceCrdt {
  const replica = SequenceCrdt.restore(snapshot.state);
  pending.forEach((op) => replica.apply(op));
  return replica;
}

export class EditorSync {
  replica = new SequenceCrdt();
  private socket: WebSocket | null = null;
  private actor: string | null = null;
  private sequence = 0;
  private clock = 0;
  private pending = new Map<string, Operation>();
  private inflight = false;
  private sentAt = 0;
  private retryAt = 0;
  private attempt = 0;
  private retry: ReturnType<typeof setTimeout> | undefined;
  private heartbeat: ReturnType<typeof setInterval> | undefined;
  private stopped = false;
  private paused = false;
  private composing = false;
  private deferred: Message[] = [];
  private snapshotLoading = false;
  private cursor: { anchor: Id | null; focus: Id | null } = {
    anchor: null,
    focus: null,
  };
  private cursorSentAt = 0;
  private view: View = {
    text: "",
    status: "Loading document",
    role: "VIEWER",
    pending: 0,
    watermark: 0,
    users: [],
    error: "",
    ready: false,
  };
  constructor(
    private doc: string,
    private account: Account,
    private change: (view: View) => void,
  ) {}
  async start(): Promise<void> {
    try {
      const snapshot = await api<Snapshot>(
        `/documents/${this.doc}/snapshot`,
        this.account,
      );
      if (this.stopped) return;
      this.replica = SequenceCrdt.restore(snapshot.state);
      this.view.watermark = snapshot.watermark;
      this.publish();
      await this.connect();
      if (!this.stopped)
        this.heartbeat = setInterval(() => {
          this.presence(true);
          if (this.inflight && Date.now() - this.sentAt > 5000)
            this.inflight = false;
          this.flush();
        }, 1000);
    } catch (error) {
      this.fail(error);
    }
  }
  private async connect(): Promise<void> {
    if (this.stopped || this.paused) return;
    this.view.ready = false;
    this.view.status = "Connecting";
    this.publish();
    try {
      const ticket = await api<Ticket>(
        `/documents/${this.doc}/connections`,
        this.account,
        { actorId: this.actor },
      );
      if (this.stopped || this.paused) return;
      this.actor = ticket.actorId;
      this.sequence = Math.max(this.sequence, ticket.nextSequence - 1);
      this.clock = Math.max(this.clock, ticket.maxLamport);
      const ws = new WebSocket(
        `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}${ticket.socketPath}`,
        ["collab.v1", `ticket.${ticket.ticket}`],
      );
      this.socket = ws;
      ws.onopen = () => {
        this.inflight = false;
        this.send("JOIN_DOCUMENT", { afterSequence: this.view.watermark });
      };
      ws.onmessage = (event) => {
        if (this.socket !== ws || this.stopped) return;
        try {
          const message = JSON.parse(String(event.data)) as Message;
          if (message.protocolVersion !== 1 || message.documentId !== this.doc)
            throw Error("Unexpected server message");
          if (
            this.composing &&
            (message.type === "SYNC_RESPONSE" ||
              message.type === "SYNC_REQUIRED")
          )
            this.deferred.push(message);
          else this.receive(message);
        } catch (error) {
          this.fail(error);
        }
      };
      ws.onclose = () => {
        if (this.socket !== ws) return;
        this.view.ready = false;
        this.inflight = false;
        this.view.users = [];
        if (!this.stopped && !this.paused) this.scheduleReconnect();
        this.publish();
      };
      ws.onerror = () => {
        this.view.status = "Connection interrupted";
        this.publish();
      };
    } catch (error) {
      if (
        error instanceof ApiError &&
        (error.status === 401 || error.status === 403 || error.status === 404)
      )
        this.fail(error);
      else this.scheduleReconnect();
    }
  }
  private scheduleReconnect(): void {
    if (this.stopped || this.paused) return;
    clearTimeout(this.retry);
    this.view.status = "Reconnecting";
    this.retry = setTimeout(
      () => {
        void this.connect();
      },
      Math.min(15000, 500 * 2 ** this.attempt++),
    );
    this.publish();
  }
  private receive(message: Message): void {
    if (message.type === "SYNC_RESPONSE") {
      const page = message.data as Page;
      this.view.watermark = applyPage(this.replica, this.view.watermark, page);
      this.view.role = page.role;
      this.view.ready = !page.hasMore;
      this.view.status = page.hasMore ? "Synchronizing" : "Connected";
      this.attempt = 0;
      if (page.hasMore)
        this.send("SYNC_REQUEST", { afterSequence: this.view.watermark });
      else this.flush();
    } else if (message.type === "ACK") {
      const data = message.data as { acknowledgments: { operationId: Id }[] };
      data.acknowledgments.forEach((ack) =>
        this.pending.delete(key(ack.operationId)),
      );
      this.inflight = false;
      this.view.error = "";
      this.flush();
    } else if (message.type === "SYNC_REQUIRED") {
      void this.reloadSnapshot();
    } else if (message.type === "PRESENCE") {
      const data = message.data as { users: Presence[]; available: boolean };
      this.view.users = data.users;
      this.view.presenceAvailable = data.available;
    } else if (message.type === "ERROR") {
      const code = (message.data as { code: string }).code;
      if (code === "RATE_LIMITED" || code === "RATE_LIMIT_UNAVAILABLE") {
        this.inflight = false;
        this.retryAt = Date.now() + 1500;
        this.view.error =
          "Edits are waiting for the service. Your pending changes remain in this tab.";
      } else
        this.fail(
          Error(
            `${code.replaceAll("_", " ")}. Pending changes remain in this tab; export your draft before leaving.`,
          ),
        );
    } else throw Error("Unknown server message");
    this.publish();
  }
  private async reloadSnapshot(): Promise<void> {
    if (this.snapshotLoading) return;
    this.snapshotLoading = true;
    this.view.ready = false;
    this.view.status = "Recovering snapshot";
    this.publish();
    try {
      const snapshot = await api<Snapshot>(
        `/documents/${this.doc}/snapshot`,
        this.account,
      );
      if (this.stopped) return;
      this.replica = recover(snapshot, [...this.pending.values()]);
      this.view.watermark = snapshot.watermark;
      this.inflight = false;
      this.send("SYNC_REQUEST", { afterSequence: snapshot.watermark });
      this.publish();
    } catch (error) {
      this.fail(error);
    } finally {
      this.snapshotLoading = false;
    }
  }
  edit(value: string): void {
    if (!this.view.ready || this.view.role === "VIEWER" || !this.actor) {
      this.publish();
      return;
    }
    try {
      const operations = editOperations(
        this.replica,
        value,
        this.actor,
        this.sequence,
        this.clock,
      );
      // Validate the complete local batch before mutating the visible replica.
      const candidate = SequenceCrdt.restore(this.replica.snapshot());
      operations.forEach((op) => candidate.apply(op));
      this.replica = candidate;
      for (const op of operations) {
        this.sequence = op.operationId.counter;
        this.clock = op.lamport;
        this.pending.set(key(op.operationId), op);
      }
      this.view.error = "";
      this.flush();
      this.publish();
    } catch (error) {
      this.view.error =
        error instanceof Error ? error.message : "Edit rejected";
      this.publish();
    }
  }
  private flush(): void {
    if (
      !this.view.ready ||
      this.view.role === "VIEWER" ||
      this.inflight ||
      this.pending.size === 0 ||
      this.socket?.readyState !== WebSocket.OPEN ||
      Date.now() < this.retryAt
    )
      return;
    this.inflight = true;
    this.sentAt = Date.now();
    this.send("EDIT", { operations: [...this.pending.values()].slice(0, 32) });
  }
  private send(type: string, fields: object): void {
    if (this.socket?.readyState === WebSocket.OPEN)
      this.socket.send(
        JSON.stringify({
          protocolVersion: 1,
          type,
          documentId: this.doc,
          actorId: this.actor,
          ...fields,
        }),
      );
  }
  selection(start: number, end: number): void {
    const nodes = this.replica.visible(),
      text = nodes.map((n) => n.value).join("");
    const anchor = Array.from(text.slice(0, start)).length - 1,
      focus = Array.from(text.slice(0, end)).length - 1;
    this.cursor = {
      anchor: nodes[anchor]?.id ?? null,
      focus: nodes[focus]?.id ?? null,
    };
    this.presence(false);
  }
  private presence(heartbeat: boolean): void {
    const now = Date.now();
    if (!this.view.ready || now - this.cursorSentAt < (heartbeat ? 10000 : 100))
      return;
    this.cursorSentAt = now;
    this.send("PRESENCE", { presence: this.cursor });
  }
  beginComposition(): void {
    this.composing = true;
  }
  endComposition(value: string): void {
    this.edit(value);
    this.composing = false;
    const messages = this.deferred;
    this.deferred = [];
    try {
      messages.forEach((msg) => this.receive(msg));
    } catch (error) {
      this.fail(error);
    }
  }
  pause(): void {
    this.paused = true;
    clearTimeout(this.retry);
    this.socket?.close();
    this.view.ready = false;
    this.view.status = "Disconnected";
    this.publish();
  }
  resume(): void {
    if (this.stopped) return;
    this.paused = false;
    void this.connect();
  }
  stop(): void {
    this.stopped = true;
    clearTimeout(this.retry);
    clearInterval(this.heartbeat);
    this.socket?.close();
  }
  private fail(error: unknown): void {
    this.view.error =
      error instanceof Error ? error.message : "Synchronization failed";
    this.view.status = "Attention needed";
    this.view.ready = false;
    this.stop();
    this.publish();
  }
  private publish(): void {
    this.view.text = this.replica.text();
    this.view.pending = this.pending.size;
    this.change({ ...this.view, users: [...this.view.users] });
  }
}
