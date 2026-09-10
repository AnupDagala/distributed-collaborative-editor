import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { createRoot } from "react-dom/client";
import { api } from "./api";
import type { Account } from "./api";
import { EditorSync } from "./sync";
import type { Role, View } from "./sync";
import { key } from "./crdt";
import type { Id } from "./crdt";
import "./styles.css";

interface Document {
  id: string;
  title: string;
  role: Role;
  sequence: number;
  updatedAt: string;
}
interface Member {
  userId: string;
  email: string;
  role: Role;
}
function navigate(path: string): void {
  history.pushState(null, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}
function usePath(): string {
  const [path, setPath] = useState(location.pathname);
  useEffect(() => {
    const changed = () => setPath(location.pathname);
    window.addEventListener("popstate", changed);
    return () => window.removeEventListener("popstate", changed);
  }, []);
  return path;
}
function message(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Something went wrong. Please retry.";
}
function loadAccount(): Account | null {
  try {
    const raw = sessionStorage.getItem("collab.account");
    const account = raw ? (JSON.parse(raw) as Account) : null;
    return account && new Date(account.expiresAt).getTime() > Date.now()
      ? account
      : null;
  } catch {
    return null;
  }
}
function App() {
  const [account, setAccount] = useState<Account | null>(loadAccount);
  const path = usePath();
  function authenticated(next: Account) {
    sessionStorage.setItem("collab.account", JSON.stringify(next));
    setAccount(next);
    navigate("/documents");
  }
  function logout() {
    sessionStorage.removeItem("collab.account");
    setAccount(null);
    navigate("/login");
  }
  return (
    <>
      <header className="app-header">
        <a
          className="brand"
          href="/documents"
          onClick={(e) => {
            e.preventDefault();
            navigate("/documents");
          }}
        >
          <span className="brand-mark" aria-hidden="true">
            c
          </span>
          common<span className="brand-note">a shared place to write</span>
        </a>
        {account && (
          <div className="account">
            <span>{account.email}</span>
            <button className="text-button" onClick={logout}>
              Sign out
            </button>
          </div>
        )}
      </header>
      {!account ? (
        <Auth register={path === "/register"} done={authenticated} />
      ) : path.match(/^\/documents\/[0-9a-f-]+$/) ? (
        <Editor key={path} id={path.split("/")[2]} account={account} />
      ) : (
        <Documents account={account} />
      )}
      <footer className="app-footer">
        <span>Distributed Collaborative Editing Engine</span>
        <span>Shared words. Independent replicas.</span>
      </footer>
    </>
  );
}
function Auth({
  register,
  done,
}: {
  register: boolean;
  done: (a: Account) => void;
}) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError("");
    try {
      done(
        await api<Account>(`/auth/${register ? "register" : "login"}`, null, {
          email: form.get("email"),
          password: form.get("password"),
        }),
      );
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="auth-layout">
      <section className="auth-intro">
        <p className="eyebrow">THINK TOGETHER, WRITE TOGETHER</p>
        <h1>
          A little space.
          <br />A shared idea.
          <br />
          <em>A common thread.</em>
        </h1>
        <p>
          Bring your words together in a document that keeps everyone in sync.
        </p>
        <div className="sample-note">
          <span className="note-label">A NOTE IN PROGRESS</span>
          <p>
            Good ideas rarely arrive
            <br />
            fully formed. <span className="sample-caret">They grow here.</span>
          </p>
          <span className="note-foot">
            Built for more than one point of view.
          </span>
        </div>
      </section>
      <section className="auth-card">
        <p className="eyebrow">YOUR WORKSPACE</p>
        <h2>{register ? "Make yourself at home." : "Welcome back."}</h2>
        <p className="muted">
          {register
            ? "Create an account to start a shared document."
            : "Sign in to pick up where you left off."}
        </p>
        <form
          onSubmit={(e) => {
            void submit(e);
          }}
        >
          <label>
            Email address
            <input
              name="email"
              type="email"
              autoComplete="email"
              required
              maxLength={254}
              placeholder="you@example.com"
            />
          </label>
          <label>
            Password
            <input
              name="password"
              type="password"
              autoComplete={register ? "new-password" : "current-password"}
              required
              minLength={12}
              maxLength={72}
              placeholder="At least 12 characters"
            />
          </label>
          {register && (
            <p className="field-hint">
              12 characters minimum, 72 UTF-8 bytes maximum.
            </p>
          )}
          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}
          <button className="primary full" disabled={busy}>
            {busy ? "One moment…" : register ? "Create account →" : "Sign in →"}
          </button>
        </form>
        <p className="auth-switch">
          {register ? "Already have an account?" : "New to Common?"}{" "}
          <button
            className="text-button"
            onClick={() => {
              setError("");
              navigate(register ? "/login" : "/register");
            }}
          >
            {register ? "Sign in" : "Create an account"}
          </button>
        </p>
        <p className="privacy-note">
          Your documents are private until you invite someone.
        </p>
      </section>
    </main>
  );
}
function Documents({ account }: { account: Account }) {
  const [documents, setDocuments] = useState<Document[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [offset, setOffset] = useState(0);
  const refresh = () =>
    api<Document[]>(`/documents?offset=${offset}&limit=30`, account)
      .then(setDocuments)
      .catch((e) => setError(message(e)));
  useEffect(() => {
    void refresh();
  }, [account, offset]);
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const form = new FormData(event.currentTarget);
    try {
      const doc = await api<Document>("/documents", account, {
        title: form.get("title"),
      });
      navigate(`/documents/${doc.id}`);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  async function remove(doc: Document) {
    if (
      !confirm(
        `Delete “${doc.title}”? It will no longer be accessible to collaborators.`,
      )
    )
      return;
    try {
      await api(`/documents/${doc.id}`, account, undefined, "DELETE");
      void refresh();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <main className="workspace">
      <div className="workspace-heading">
        <div>
          <p className="eyebrow">YOUR SHARED WORKSPACE</p>
          <h1>Room for your next idea.</h1>
          <p className="muted">
            Open a document. Invite a collaborator. Start a conversation in
            words.
          </p>
        </div>
        <span className="workspace-number" aria-hidden="true">
          01 /
        </span>
      </div>
      <form
        className="create-document"
        onSubmit={(e) => {
          void create(e);
        }}
      >
        <label htmlFor="document-title">Start something new</label>
        <div>
          <input
            id="document-title"
            name="title"
            required
            maxLength={160}
            placeholder="Give your document a title"
          />
          <button className="primary" disabled={busy}>
            {busy ? "Creating…" : "+ New document"}
          </button>
        </div>
      </form>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div className="section-heading">
        <h2>Documents</h2>
        <span className="muted">Owned by you & shared with you</span>
      </div>
      {documents.length === 0 ? (
        <div className="empty-state">
          <span aria-hidden="true">▤</span>
          <h3>A blank page is a good beginning.</h3>
          <p>
            Create your first document above, or ask a teammate to invite your
            email.
          </p>
        </div>
      ) : (
        <div className="document-list">
          {documents.map((doc) => (
            <article className="document-row" key={doc.id}>
              <span className="document-icon" aria-hidden="true">
                ▤
              </span>
              <button
                className="document-link"
                onClick={() => navigate(`/documents/${doc.id}`)}
              >
                <strong>{doc.title}</strong>
                <span>
                  Updated {new Date(doc.updatedAt).toLocaleString()} · Version{" "}
                  {doc.sequence}
                </span>
              </button>
              <span className="role-tag">{doc.role.toLowerCase()}</span>
              {doc.role === "OWNER" && (
                <button
                  className="text-button danger"
                  aria-label={`Delete ${doc.title}`}
                  onClick={() => {
                    void remove(doc);
                  }}
                >
                  Delete
                </button>
              )}
              <button
                className="open-button"
                aria-label={`Open ${doc.title}`}
                onClick={() => navigate(`/documents/${doc.id}`)}
              >
                ↗
              </button>
            </article>
          ))}
        </div>
      )}
      <div className="pagination">
        <button
          className="text-button"
          disabled={offset === 0}
          onClick={() => setOffset(Math.max(0, offset - 30))}
        >
          ← Previous
        </button>
        <button
          className="text-button"
          disabled={documents.length < 30}
          onClick={() => setOffset(offset + 30)}
        >
          Next →
        </button>
      </div>
    </main>
  );
}
function Editor({ id, account }: { id: string; account: Account }) {
  const [doc, setDoc] = useState<Document | null>(null),
    [members, setMembers] = useState<Member[]>([]),
    [panel, setPanel] = useState<"people" | "history">("people");
  const [view, setView] = useState<View>({
    text: "",
    status: "Loading document",
    role: "VIEWER",
    pending: 0,
    watermark: 0,
    users: [],
    error: "",
    ready: false,
  });
  const [pageError, setPageError] = useState(""),
    [paused, setPaused] = useState(false);
  const engine = useRef<EditorSync | null>(null),
    textarea = useRef<HTMLTextAreaElement | null>(null),
    composition = useRef(false),
    localEdit = useRef(false),
    current = useRef(view);
  const refreshMembers = () =>
    api<Member[]>(`/documents/${id}/members`, account)
      .then(setMembers)
      .catch((e) => setPageError(message(e)));
  useEffect(() => {
    void api<Document>(`/documents/${id}`, account)
      .then(setDoc)
      .catch((e) => setPageError(message(e)));
    void refreshMembers();
    let previousNodes: { id: Id; value: string }[] = [];
    const sync = new EditorSync(id, account, (next) => {
      const element = textarea.current;
      let anchors: (Id | null)[] | null = null;
      if (
        element &&
        document.activeElement === element &&
        !localEdit.current &&
        !composition.current &&
        next.text !== current.current.text
      ) {
        anchors = [element.selectionStart, element.selectionEnd].map(
          (offset) =>
            previousNodes[
              Array.from(current.current.text.slice(0, offset)).length - 1
            ]?.id ?? null,
        );
      }
      previousNodes = sync.replica.visible();
      current.current = next;
      setView(next);
      if (anchors) {
        const saved = anchors;
        requestAnimationFrame(() => {
          if (!textarea.current) return;
          const nodes = sync.replica.visible();
          const offset = (anchor: Id | null) => {
            if (!anchor) return 0;
            const index = nodes.findIndex((n) => key(n.id) === key(anchor));
            return index < 0
              ? Math.min(textarea.current!.selectionStart, next.text.length)
              : nodes
                  .slice(0, index + 1)
                  .map((n) => n.value)
                  .join("").length;
          };
          textarea.current.setSelectionRange(
            offset(saved[0]),
            offset(saved[1]),
          );
        });
      }
    });
    engine.current = sync;
    void sync.start();
    const warn = (event: BeforeUnloadEvent) => {
      if (current.current.pending > 0) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", warn);
    return () => {
      sync.stop();
      window.removeEventListener("beforeunload", warn);
    };
  }, [id, account]);
  function back() {
    if (
      view.pending &&
      !confirm(
        "Some changes are still pending. Export your draft before leaving. Leave anyway?",
      )
    )
      return;
    navigate("/documents");
  }
  function exportDraft() {
    const url = URL.createObjectURL(
      new Blob([view.text], { type: "text/plain;charset=utf-8" }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = "common-draft.txt";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  const online = new Set(view.users.map((u) => u.userId));
  return (
    <main className="editor-workspace">
      <nav className="editor-nav">
        <button className="text-button" onClick={back}>
          ← All documents
        </button>
        <div>
          <button className="text-button" onClick={exportDraft}>
            Export draft
          </button>
          <button
            className="text-button"
            disabled={view.status === "Attention needed"}
            onClick={() => {
              if (paused) engine.current?.resume();
              else engine.current?.pause();
              setPaused(!paused);
            }}
          >
            {paused ? "Reconnect" : "Disconnect"}
          </button>
        </div>
      </nav>
      <div className="editor-heading">
        <div>
          <p className="eyebrow">A WORK IN COMMON</p>
          <h1>{doc?.title ?? "Opening document…"}</h1>
        </div>
        <div className="connection-status" role="status">
          <span className={`status-dot ${view.ready ? "connected" : ""}`} />
          {view.status}
        </div>
      </div>
      <div className="editor-toolbar">
        <div>
          <span className="role-tag">{view.role.toLowerCase()}</span>
          <span>
            {view.pending
              ? `${view.pending} pending edits`
              : "All received edits saved"}
          </span>
        </div>
        <span>
          Version <strong>{view.watermark}</strong>
        </span>
      </div>
      {(pageError || view.error) && (
        <p className="error" role="alert">
          {pageError || view.error}
        </p>
      )}
      <div className="editor-columns">
        <section className="paper">
          <div className="paper-top">
            <span>DOCUMENT</span>
            <span>
              {view.role === "VIEWER"
                ? "Read only"
                : "Plain text · Live collaboration"}
            </span>
          </div>
          <textarea
            ref={textarea}
            aria-label="Document text"
            spellCheck
            placeholder={
              view.ready
                ? "Every shared idea starts with a first sentence…"
                : "Connecting to your document…"
            }
            readOnly={!view.ready || view.role === "VIEWER"}
            value={view.text}
            onChange={(event) => {
              if (composition.current) {
                setView((v) => ({ ...v, text: event.target.value }));
                return;
              }
              localEdit.current = true;
              engine.current?.edit(event.target.value);
              localEdit.current = false;
            }}
            onSelect={(event) =>
              engine.current?.selection(
                event.currentTarget.selectionStart,
                event.currentTarget.selectionEnd,
              )
            }
            onCompositionStart={() => {
              composition.current = true;
              engine.current?.beginComposition();
            }}
            onCompositionEnd={(event) => {
              composition.current = false;
              localEdit.current = true;
              engine.current?.endComposition(event.currentTarget.value);
              localEdit.current = false;
            }}
          />
          <div className="paper-bottom">
            <span>
              {view.text.trim() ? view.text.trim().split(/\s+/).length : 0}{" "}
              words · {Array.from(view.text).length} characters
            </span>
            <span>
              {view.pending
                ? "Keep this tab open until saved"
                : "Your words, in sync"}
            </span>
          </div>
        </section>
        <aside className="collaboration-panel">
          <div className="panel-tabs">
            <button
              className={panel === "people" ? "active" : ""}
              onClick={() => setPanel("people")}
            >
              People
            </button>
            <button
              className={panel === "history" ? "active" : ""}
              onClick={() => setPanel("history")}
            >
              History
            </button>
          </div>
          {panel === "people" ? (
            <>
              <div className="panel-section">
                <p className="eyebrow">IN THIS DOCUMENT</p>
                {members.map((member, index) => (
                  <div className="member-row" key={member.userId}>
                    <span className={`avatar color-${index % 3}`}>
                      {member.email.slice(0, 1).toUpperCase()}
                    </span>
                    <div>
                      <strong>
                        {member.email === account.email
                          ? "You"
                          : member.email.split("@")[0]}
                      </strong>
                      <span>
                        {member.role.toLowerCase()} ·{" "}
                        {online.has(member.userId) ? "online" : "away"}
                      </span>
                    </div>
                    {view.role === "OWNER" && member.role !== "OWNER" && (
                      <button
                        className="remove-member"
                        aria-label={`Remove ${member.email}`}
                        onClick={() => {
                          void api(
                            `/documents/${id}/members/${member.userId}`,
                            account,
                            undefined,
                            "DELETE",
                          )
                            .then(refreshMembers)
                            .catch((e) => setPageError(message(e)));
                        }}
                      >
                        ×
                      </button>
                    )}
                  </div>
                ))}
                <p className="field-hint">
                  {view.presenceAvailable === false
                    ? "Presence temporarily unavailable"
                    : `${view.users.length} active ${view.users.length === 1 ? "session" : "sessions"}`}
                </p>
              </div>
              {view.role === "OWNER" && (
                <ShareForm id={id} account={account} changed={refreshMembers} />
              )}
              <div className="panel-tip">
                <span aria-hidden="true">↔</span>
                <h3>Better, together.</h3>
                <p>
                  Invite an existing account as an editor to write together, or
                  as a viewer to share a work in progress.
                </p>
              </div>
            </>
          ) : (
            <History id={id} account={account} version={view.watermark} />
          )}
        </aside>
      </div>
    </main>
  );
}
function ShareForm({
  id,
  account,
  changed,
}: {
  id: string;
  account: Account;
  changed: () => Promise<void>;
}) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget,
      form = new FormData(formElement);
    setBusy(true);
    setError("");
    try {
      await api(`/documents/${id}/members`, account, {
        email: form.get("email"),
        role: form.get("role"),
      });
      await changed();
      formElement.reset();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form
      className="panel-section share-form"
      onSubmit={(e) => {
        void submit(e);
      }}
    >
      <h3>Invite a collaborator</h3>
      <label>
        Email address
        <input
          name="email"
          type="email"
          placeholder="teammate@example.com"
          required
          maxLength={254}
        />
      </label>
      <label>
        Permission
        <select name="role" defaultValue="EDITOR">
          <option value="EDITOR">Editor — can write</option>
          <option value="VIEWER">Viewer — can read</option>
        </select>
      </label>
      <button className="secondary full" disabled={busy}>
        {busy ? "Inviting…" : "Grant access"}
      </button>
      <p className="field-hint">
        Access is granted immediately. No email is sent.
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
function History({
  id,
  account,
  version,
}: {
  id: string;
  account: Account;
  version: number;
}) {
  const [after, setAfter] = useState(0),
    [error, setError] = useState("");
  const [page, setPage] = useState<{
    operations: {
      serverSequence: number;
      operation: { type: string; operationId: Id };
      createdAt: string;
    }[];
    hasMore: boolean;
    throughSequence: number;
  } | null>(null);
  useEffect(() => {
    void api<typeof page>(
      `/documents/${id}/history?after=${after}&limit=30`,
      account,
    )
      .then(setPage)
      .catch((e) => setError(message(e)));
  }, [id, account, after, version]);
  return (
    <div className="panel-section">
      <p className="eyebrow">DURABLE EDIT HISTORY</p>
      <p className="field-hint">Each version is one stored insert or delete.</p>
      {error && <p className="error">{error}</p>}
      {page?.operations.length === 0 && (
        <p className="muted">The first edit will appear here.</p>
      )}
      <ol className="history-list">
        {page?.operations.map((entry) => (
          <li key={entry.serverSequence}>
            <strong>
              v{entry.serverSequence} · {entry.operation.type.toLowerCase()}
            </strong>
            <span>
              Actor {entry.operation.operationId.actor.slice(0, 8)} · #
              {entry.operation.operationId.counter}
            </span>
            <time>{new Date(entry.createdAt).toLocaleTimeString()}</time>
          </li>
        ))}
      </ol>
      <div className="pagination">
        <button
          className="text-button"
          disabled={after === 0}
          onClick={() => setAfter(Math.max(0, after - 30))}
        >
          ←
        </button>
        <button
          className="text-button"
          disabled={!page?.hasMore}
          onClick={() => setAfter(page?.throughSequence ?? 0)}
        >
          Next →
        </button>
      </div>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
