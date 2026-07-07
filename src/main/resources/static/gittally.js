// GitTally web UI — polls the JSON API and re-renders table bodies from data.
// Every fetch has a timeout and failures render an explicit error badge, so the
// UI can never get stuck on a loading animation (the legacy defect).
"use strict";

// ---- pure helpers ----------------------------------------------------------

function formatDuration(totalSeconds) {
    if (totalSeconds == null || Number.isNaN(totalSeconds) || totalSeconds < 0) {
        return "";
    }
    const seconds = Math.floor(totalSeconds);
    const two = (n) => String(n).padStart(2, "0");
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const rest = seconds % 60;
    return hours > 0 ? `${hours}:${two(minutes)}:${two(rest)}` : `${minutes}:${two(rest)}`;
}

function formatTimestamp(iso) {
    if (!iso) {
        return "";
    }
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
        return iso;
    }
    const two = (n) => String(n).padStart(2, "0");
    return `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())}` +
        ` ${two(date.getHours())}:${two(date.getMinutes())}`;
}

function abbrevCommit(commit) {
    return (commit || "").slice(0, 12);
}

const KNOWN_STATUSES = new Set(
    ["success", "failed", "running", "pending", "interrupted", "cancelled", "unknown", "error", "finished"],
);

function statusCssClass(status) {
    return "status status-" + (KNOWN_STATUSES.has(status) ? status : "unknown");
}

// ---- shared infrastructure -------------------------------------------------

const FETCH_TIMEOUT_MS = 8000;
const TABLE_POLL_MS = 10000;
const CURRENT_POLL_MS = 3000;

function metaContent(name) {
    const element = document.querySelector(`meta[name="${name}"]`);
    return element ? element.content : "";
}

const controlToken = metaContent("gittally-control-token");
const giteaRepoUrl = metaContent("gittally-gitea-repo-url");

async function fetchJson(url) {
    const response = await fetch(url, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
    if (!response.ok) {
        throw new Error("HTTP " + response.status);
    }
    return response.json();
}

async function sendAction(url, method) {
    const response = await fetch(url, {
        method,
        headers: { "X-GitTally-Token": controlToken },
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
    if (!response.ok) {
        throw new Error("HTTP " + response.status);
    }
}

function setLiveIndicator(ok, detail) {
    const indicator = document.getElementById("live-indicator");
    if (!indicator) {
        return;
    }
    indicator.className = ok ? "status status-success" : "status status-error";
    indicator.textContent = ok ? "live" : "error";
    indicator.title = detail || "";
    document.querySelectorAll("tbody").forEach((tbody) => tbody.classList.toggle("is-stale", !ok));
}

// The page's refresh function; actions trigger it for an immediate update.
let refreshNow = null;

/** Polls `refresh`; paused while the tab is hidden, refreshed immediately when visible again. */
function startPolling(refresh, intervalMs) {
    let timer = null;
    const tick = () => {
        refresh()
            .then(() => setLiveIndicator(true, "last update " + formatTimestamp(new Date().toISOString())))
            .catch((error) => setLiveIndicator(false, String(error)));
    };
    const start = () => {
        if (timer === null) {
            tick();
            timer = setInterval(tick, intervalMs);
        }
    };
    const stop = () => {
        if (timer !== null) {
            clearInterval(timer);
            timer = null;
        }
    };
    document.addEventListener("visibilitychange", () => (document.hidden ? stop() : start()));
    refreshNow = tick;
    start();
}

// ---- DOM building (textContent only — data can never inject markup) --------

function elem(tag, className, text) {
    const element = document.createElement(tag);
    if (className) {
        element.className = className;
    }
    if (text != null) {
        element.textContent = text;
    }
    return element;
}

function externalLink(href, text) {
    const anchor = elem("a", null, text);
    anchor.href = href;
    anchor.target = "_blank";
    anchor.rel = "noopener noreferrer";
    return anchor;
}

function copyButton(value, label) {
    const button = elem("button", "copy-button", "⧉");
    button.type = "button";
    button.dataset.copy = value;
    button.title = "Copy " + label;
    button.setAttribute("aria-label", "Copy " + label);
    return button;
}

function statusBadge(status) {
    return elem("span", statusCssClass(status), status);
}

function actionButton(symbol, title, className, dataset) {
    const button = elem("button", "action-button" + (className ? " " + className : ""), symbol);
    button.type = "button";
    button.title = title;
    button.setAttribute("aria-label", title);
    Object.assign(button.dataset, dataset);
    return button;
}

// ---- latest/history table --------------------------------------------------

function renderBuildRow(build, allowRestart) {
    const row = document.createElement("tr");
    row.dataset.artifactKey = build.artifactKey || "";
    row.dataset.branch = build.branch;
    row.dataset.startedAt = build.startedAt || "";
    row.dataset.status = build.status || "unknown";

    const statusCell = elem("td");
    statusCell.dataset.label = "Status";
    statusCell.appendChild(statusBadge(build.status || "unknown"));
    row.appendChild(statusCell);

    const branchCell = elem("td", "branch");
    branchCell.dataset.label = "Branch";
    const branchTools = elem("span", "link-tools");
    branchTools.appendChild(
        giteaRepoUrl
            ? externalLink(giteaRepoUrl + "/src/branch/" + encodeBranchPath(build.branch), build.branch)
            : elem("span", null, build.branch),
    );
    branchTools.appendChild(copyButton(build.branch, "branch name"));
    branchCell.appendChild(branchTools);
    row.appendChild(branchCell);

    const commitCell = elem("td");
    commitCell.dataset.label = "Commit";
    const commitTools = elem("span", "link-tools");
    const commitCode = elem("code");
    commitCode.appendChild(
        giteaRepoUrl
            ? externalLink(giteaRepoUrl + "/commit/" + encodeURIComponent(build.commit), abbrevCommit(build.commit))
            : elem("span", null, abbrevCommit(build.commit)),
    );
    commitTools.appendChild(commitCode);
    commitTools.appendChild(copyButton(build.commit, "full commit ID"));
    commitCell.appendChild(commitTools);
    row.appendChild(commitCell);

    const startedCell = elem("td", null, formatTimestamp(build.startedAt));
    startedCell.dataset.label = "Started";
    row.appendChild(startedCell);

    const durationCell = elem("td", "duration-cell", formatDuration(build.durationSeconds));
    durationCell.dataset.label = "Duration";
    row.appendChild(durationCell);

    const artifactsCell = elem("td");
    artifactsCell.dataset.label = "Artifacts";
    if (build.artifactKey) {
        const artifactLink = elem("a", "artifact-link", "📄");
        artifactLink.href = "/builds/" + encodeURIComponent(build.artifactKey);
        artifactLink.title = "Open artifacts";
        artifactsCell.appendChild(artifactLink);
    } else {
        artifactsCell.textContent = "n/a";
    }
    row.appendChild(artifactsCell);

    const actionsCell = elem("td", "actions-cell");
    const actions = elem("div", "actions");
    if (allowRestart) {
        actions.appendChild(actionButton("↻", "Restart build", null, { action: "restart", branch: build.branch }));
    }
    if (build.artifactKey) {
        actions.appendChild(
            actionButton("×", "Delete stored build", "delete-button", {
                action: "delete",
                artifactKey: build.artifactKey,
            }),
        );
    }
    actionsCell.appendChild(actions);
    row.appendChild(actionsCell);
    return row;
}

function encodeBranchPath(branch) {
    return (branch || "").split("/").map(encodeURIComponent).join("/");
}

function initBuildsTable() {
    const table = document.getElementById("builds-table");
    if (!table) {
        return;
    }
    const tbody = document.getElementById("build-rows");
    const allowRestart = table.dataset.allowRestart === "true";

    async function refresh() {
        const builds = await fetchJson(table.dataset.api);
        tbody.replaceChildren();
        if (builds.length === 0) {
            const cell = elem("td", "empty", table.dataset.emptyMessage || "No builds recorded yet.");
            cell.colSpan = 7;
            tbody.appendChild(elem("tr")).appendChild(cell);
            return;
        }
        builds.forEach((build) => tbody.appendChild(renderBuildRow(build, allowRestart)));
    }

    startPolling(refresh, TABLE_POLL_MS);
}

// ---- current builds with live logs ------------------------------------------

function renderBuildCard(build) {
    const card = elem("section", "build-card");
    card.dataset.artifactKey = build.artifactKey;
    card.dataset.startedAt = build.startedAt || "";
    card.dataset.status = build.status || "running";

    const header = elem("header", "build-card-header");
    header.appendChild(statusBadge(build.status || "running"));
    const branchTools = elem("span", "branch link-tools");
    branchTools.appendChild(
        giteaRepoUrl
            ? externalLink(giteaRepoUrl + "/src/branch/" + encodeBranchPath(build.branch), build.branch)
            : elem("span", null, build.branch),
    );
    header.appendChild(branchTools);
    const commitCode = elem("code");
    commitCode.appendChild(
        giteaRepoUrl
            ? externalLink(giteaRepoUrl + "/commit/" + encodeURIComponent(build.commit), abbrevCommit(build.commit))
            : elem("span", null, abbrevCommit(build.commit)),
    );
    header.appendChild(commitCode);
    header.appendChild(elem("span", "muted", "started " + formatTimestamp(build.startedAt)));
    header.appendChild(elem("span", "duration-cell running-duration"));
    const cardActions = elem("span", "build-card-actions");
    cardActions.appendChild(
        actionButton("× Cancel", "Cancel build", "cancel-button", {
            action: "cancel",
            artifactKey: build.artifactKey,
        }),
    );
    header.appendChild(cardActions);
    card.appendChild(header);
    card.appendChild(elem("pre", "live-log"));
    return card;
}

/** A build that left the current list is finished — link to its result instead of dropping the card. */
function markCardFinished(card) {
    if (card.dataset.status === "finished") {
        return;
    }
    card.dataset.status = "finished";
    const badge = card.querySelector(".status");
    badge.className = statusCssClass("finished");
    badge.textContent = "finished";
    card.querySelector(".cancel-button")?.remove();
    const resultNote = elem("p", "build-card-result");
    const resultLink = elem("a", null, "View result and artifacts");
    resultLink.href = "/builds/" + encodeURIComponent(card.dataset.artifactKey);
    resultNote.appendChild(resultLink);
    card.querySelector(".live-log").before(resultNote);
}

function initCurrentBuilds() {
    const container = document.getElementById("current-builds");
    if (!container) {
        return;
    }
    const noCurrent = document.getElementById("no-current");
    const logOffsets = new Map();

    function cardFor(artifactKey) {
        return container.querySelector(`.build-card[data-artifact-key="${CSS.escape(artifactKey)}"]`);
    }

    async function appendLogTail(build) {
        const pre = cardFor(build.artifactKey)?.querySelector(".live-log");
        if (!pre) {
            return;
        }
        const offset = logOffsets.get(build.artifactKey) || 0;
        const url = `/api/builds/current/${encodeURIComponent(build.artifactKey)}/log?offset=${offset}`;
        const tail = await fetchJson(url);
        logOffsets.set(build.artifactKey, tail.nextOffset);
        if (tail.content) {
            const nearBottom = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 40;
            pre.append(tail.content);
            if (nearBottom) {
                pre.scrollTop = pre.scrollHeight;
            }
        }
    }

    async function refresh() {
        const builds = await fetchJson(container.dataset.api);
        const activeKeys = new Set(builds.map((build) => build.artifactKey));
        builds.forEach((build) => {
            let card = cardFor(build.artifactKey);
            if (!card) {
                card = container.appendChild(renderBuildCard(build));
            } else {
                card.dataset.status = build.status;
                const badge = card.querySelector(".status");
                badge.className = statusCssClass(build.status);
                badge.textContent = build.status;
            }
        });
        container.querySelectorAll(".build-card").forEach((card) => {
            if (!activeKeys.has(card.dataset.artifactKey)) {
                markCardFinished(card);
            }
        });
        if (noCurrent) {
            noCurrent.style.display = container.querySelector(".build-card") ? "none" : "";
        }
        await Promise.all(builds.map(appendLogTail));
    }

    startPolling(refresh, CURRENT_POLL_MS);
}

// ---- running-duration ticking ------------------------------------------------

function tickRunningDurations() {
    const now = Date.now();
    document.querySelectorAll("[data-started-at]").forEach((element) => {
        const status = element.dataset.status;
        if (status !== "running" && status !== "pending") {
            return;
        }
        const startedAt = new Date(element.dataset.startedAt).getTime();
        const durationCell = element.querySelector(".duration-cell");
        if (durationCell && !Number.isNaN(startedAt)) {
            durationCell.textContent = formatDuration((now - startedAt) / 1000);
        }
    });
}

// ---- event delegation for copy and action buttons -----------------------------

document.addEventListener("click", (event) => {
    const button = event.target.closest(".copy-button");
    if (!button || !navigator.clipboard) {
        return;
    }
    navigator.clipboard.writeText(button.dataset.copy || "").then(() => {
        button.classList.add("is-copied");
        setTimeout(() => button.classList.remove("is-copied"), 1200);
    });
});

document.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-action]");
    if (!button) {
        return;
    }
    const action = button.dataset.action;
    if (action === "delete" && !window.confirm("Delete this build result and its stored artifacts?")) {
        return;
    }
    button.disabled = true;
    try {
        if (action === "restart") {
            await sendAction("/api/builds/restart?branch=" + encodeURIComponent(button.dataset.branch), "POST");
        } else if (action === "cancel") {
            await sendAction(`/api/builds/${encodeURIComponent(button.dataset.artifactKey)}/cancel`, "POST");
        } else if (action === "delete") {
            await sendAction("/api/builds/" + encodeURIComponent(button.dataset.artifactKey), "DELETE");
        }
        if (refreshNow) {
            refreshNow();
        }
    } catch (error) {
        setLiveIndicator(false, String(error));
    } finally {
        button.disabled = false;
    }
});

// ---- page wiring ---------------------------------------------------------------

initBuildsTable();
initCurrentBuilds();
setInterval(tickRunningDurations, 1000);
tickRunningDurations();
