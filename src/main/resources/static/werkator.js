// Werkator web UI — polls the JSON API and re-renders table bodies from data.
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

/** Two-decimal metric value like `UiFormats.metric`; "n/a" when the source is unavailable. */
function formatMetric(value) {
    return typeof value === "number" && Number.isFinite(value) ? value.toFixed(2) : "n/a";
}

function formatTimeOfDay(iso) {
    const date = new Date(iso);
    if (!iso || Number.isNaN(date.getTime())) {
        return "n/a";
    }
    const two = (n) => String(n).padStart(2, "0");
    return `${two(date.getHours())}:${two(date.getMinutes())}:${two(date.getSeconds())}`;
}

const KNOWN_STATUSES = new Set(
    ["success", "failed", "running", "pending", "interrupted", "cancelled", "unknown", "error", "finished"],
);

function statusCssClass(status) {
    return "status status-" + (KNOWN_STATUSES.has(status) ? status : "unknown");
}

/** Seconds elapsed since `startedAtIso`, or null when it is missing/invalid. */
function elapsedSeconds(startedAtIso) {
    const startedAt = new Date(startedAtIso || "").getTime();
    return Number.isNaN(startedAt) ? null : (Date.now() - startedAt) / 1000;
}

/**
 * The duration to display for a build, computed at render time so re-rendered
 * rows never show an empty cell that the ticker fills back in (visible flicker):
 * the recorded build time once finished, the live build time (since the build
 * left the queue) while running, and the wait time while pending — the latter
 * is styled italic via `duration-wait` to distinguish it from build time.
 */
function displayDurationSeconds(build) {
    if (build.status === "running") {
        return elapsedSeconds(build.runningSince || build.startedAt);
    }
    if (build.status === "pending") {
        return elapsedSeconds(build.startedAt);
    }
    return build.durationSeconds;
}

function durationCellClass(status) {
    return "duration-cell" + (status === "pending" ? " duration-wait" : "");
}

// ---- shared infrastructure -------------------------------------------------

const FETCH_TIMEOUT_MS = 8000;
const TABLE_POLL_MS = 10000;
const CURRENT_POLL_MS = 3000;
const SYSTEM_POLL_MS = 60000;
// resume events (visibilitychange, pageshow, focus) often arrive in pairs
const RESUME_MIN_GAP_MS = 1000;

function metaContent(name) {
    const element = document.querySelector(`meta[name="${name}"]`);
    return element ? element.content : "";
}

const giteaRepoUrl = metaContent("werkator-gitea-repo-url");

// Empty with one served repository, `/repos/<name>` with several (ADR 0009). Every
// path this script builds itself is prefixed with it, so an action triggered on a
// repository's page acts on that repository — the paths rendered into the DOM
// (`data-api`, artifact links) already carry it.
const repoBase = metaContent("werkator-repo-base") || "";

/** The API of the repository this page belongs to; `/api` when only one is served. */
function apiBase() {
    return repoBase ? "/api" + repoBase : "/api";
}

// The control token is deliberately NOT embedded in the pages: reading them is
// unauthenticated, so anyone could have read it out of the HTML. The operator
// pastes it once per browser from `.git/werkator/control-token` on the server;
// it is kept in localStorage and only ever sent as a request header.
const CONTROL_TOKEN_KEY = "werkator.controlToken";

// The key was named after the old product name, so the rename left every browser
// with a token under a name nothing reads any more — a secret that not even
// "forget token" can reach. Dropped on load; the token itself is unchanged on the
// server, so the one re-entry per browser is all the rename costs.
try {
    window.localStorage.removeItem("gittally.controlToken");
} catch (error) {
    // localStorage unavailable (private mode, blocked cookies) — nothing stored, nothing to drop
}

function storedControlToken() {
    try {
        return window.localStorage.getItem(CONTROL_TOKEN_KEY) || "";
    } catch (error) {
        return ""; // localStorage unavailable (private mode, blocked cookies)
    }
}

function rememberControlToken(token) {
    try {
        window.localStorage.setItem(CONTROL_TOKEN_KEY, token);
    } catch (error) {
        // not persistable — the token is asked for again on the next action
    }
}

function forgetControlToken() {
    try {
        window.localStorage.removeItem(CONTROL_TOKEN_KEY);
    } catch (error) {
        // nothing to clean up when localStorage is unavailable
    }
}

function askForControlToken() {
    const answer = window.prompt(
        "Control token — the content of .git/werkator/control-token on the Werkator host:",
        "",
    );
    return answer ? answer.trim() : "";
}

async function fetchJson(url) {
    const response = await fetch(url, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
    if (!response.ok) {
        throw new Error("HTTP " + response.status);
    }
    return response.json();
}

/** A rejected token is dropped and asked for once more, so a stale one is not a dead end. */
async function sendAction(url, method) {
    let token = storedControlToken() || askForControlToken();
    if (!token) {
        throw new Error("no control token");
    }
    let response = await sendWithToken(url, method, token);
    if (response.status === 403) {
        forgetControlToken();
        token = askForControlToken();
        if (!token) {
            throw new Error("wrong control token");
        }
        response = await sendWithToken(url, method, token);
    }
    if (!response.ok) {
        throw new Error("HTTP " + response.status);
    }
    rememberControlToken(token);
}

function sendWithToken(url, method, token) {
    return fetch(url, {
        method,
        headers: { "X-werkator-Token": token },
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
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

/**
 * Polls `refresh`; paused while the tab is hidden, refreshed immediately when the page
 * becomes visible again — the durations of running builds are ticked client-side, so a
 * page returning from the background would otherwise keep counting up a build that has
 * long finished on the server.
 *
 * Resuming re-arms the interval unconditionally instead of only when the page was
 * stopped: a backgrounded page (phone, app switch) may be frozen without ever
 * delivering the hidden event, and its throttled interval then fires whenever the
 * browser feels like it. `pageshow` and `focus` are listened to as well, because not
 * every browser reports a returning page as a visibility change.
 */
function startPolling(refresh, intervalMs) {
    let timer = null;
    let lastTickAt = 0;
    const tick = () => {
        lastTickAt = Date.now();
        refresh()
            .then(() => setLiveIndicator(true, "last update " + formatTimestamp(new Date().toISOString())))
            .catch((error) => setLiveIndicator(false, String(error)));
        // separate request, deliberately not chained: whether the watcher is healthy must
        // not depend on this view's refresh, nor delay it
        refreshWatcherBanner();
    };
    const start = () => {
        stop();
        // several resume events can arrive together; one fetch is enough for all of them
        if (Date.now() - lastTickAt >= RESUME_MIN_GAP_MS) {
            tick();
        }
        timer = setInterval(tick, intervalMs);
    };
    const stop = () => {
        if (timer !== null) {
            clearInterval(timer);
            timer = null;
        }
    };
    const resume = () => {
        if (!document.hidden) {
            start();
        }
    };
    document.addEventListener("visibilitychange", () => (document.hidden ? stop() : start()));
    window.addEventListener("pageshow", resume);
    window.addEventListener("focus", resume);
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

/**
 * What the watcher's health means for the page in front of the reader, or null while
 * nothing is wrong. Three different failures, one message: what you see is not current.
 *
 * This is not the `live-indicator`, which says whether *this browser* reaches the server.
 * A watcher that cannot fetch leaves the server perfectly reachable and every row stale,
 * which is exactly the outage that went unnoticed for 57 minutes on 2026-08-30.
 */
function watcherBannerText(state) {
    const since = state.lastPollAt ? " Last attempt " + formatTimestamp(state.lastPollAt) + "." : "";
    if (state.running === false) {
        return ["watcher stopped", "No branch is being polled; nothing below will change." + since];
    }
    if (state.lastFetchError) {
        return ["origin unreachable", "The list below is not updating." + since + " " + state.lastFetchError];
    }
    if (state.lastPollError) {
        return ["poll cycle failed", "The list below may be incomplete." + since + " " + state.lastPollError];
    }
    return null;
}

/** Never rejects: an unreachable server is the live indicator's business, not the banner's. */
async function refreshWatcherBanner() {
    const banner = document.getElementById("watcher-banner");
    if (!banner) {
        return;
    }
    let state;
    try {
        state = await fetchJson("/api/watcher");
    } catch (error) {
        // leave the banner as it stands rather than claiming health we could not confirm
        return;
    }
    const text = watcherBannerText(state);
    banner.replaceChildren();
    banner.hidden = text === null;
    if (text) {
        banner.append(elem("strong", null, text[0]), elem("span", null, text[1]));
    }
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

function renderBuildRow(build, allowRestart, restartAtOriginHead) {
    const row = document.createElement("tr");
    const displayName = build.name || build.branch;
    row.dataset.artifactKey = build.artifactKey || "";
    row.dataset.branch = displayName;
    row.dataset.startedAt = build.startedAt || "";
    row.dataset.runningSince = build.runningSince || "";
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
            ? externalLink(giteaRepoUrl + "/src/branch/" + encodeBranchPath(build.branch), displayName)
            : elem("span", null, displayName),
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

    const durationCell = elem("td", durationCellClass(build.status), formatDuration(displayDurationSeconds(build)));
    durationCell.dataset.label = "Duration";
    row.appendChild(durationCell);

    const artifactsCell = elem("td");
    artifactsCell.dataset.label = "Artifacts";
    const inProgress = build.status === "running" || build.status === "pending";
    if (build.artifactKey) {
        const artifactLink = elem("a", "artifact-link", inProgress ? "⏳" : "📄");
        artifactLink.href = repoBase + "/builds/" + encodeURIComponent(build.artifactKey);
        artifactLink.title = inProgress ? "Open build log — no artifacts yet" : "Open artifacts";
        artifactsCell.appendChild(artifactLink);
    }
    if (build.latestGreenUrl) {
        const permanentLink = elem("a", "artifact-link", "🔗");
        permanentLink.href = build.latestGreenUrl;
        permanentLink.title = "Permanent link: artifacts of the latest green build";
        artifactsCell.appendChild(permanentLink);
    }
    if (inProgress) {
        const liveLink = elem("a", "artifact-link", "📡");
        liveLink.href = "/current";
        liveLink.title = "Watch this build live";
        artifactsCell.appendChild(liveLink);
    }
    if (!build.artifactKey && !build.latestGreenUrl && !inProgress) {
        artifactsCell.textContent = "n/a";
    }
    row.appendChild(artifactsCell);

    const actionsCell = elem("td", "actions-cell");
    const actions = elem("div", "actions");
    if (allowRestart) {
        // the branches view builds the branch as it is now, the other views repeat a run
        const restartTitle = restartAtOriginHead ? "Build current head" : "Restart build";
        actions.appendChild(
            actionButton("↻", restartTitle, null, {
                action: "restart",
                branch: displayName,
                atOriginHead: restartAtOriginHead ? "true" : "false",
            }),
        );
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
    const restartAtOriginHead = table.dataset.restartAtOriginHead === "true";

    async function refresh() {
        const builds = await fetchJson(table.dataset.api);
        tbody.replaceChildren();
        if (builds.length === 0) {
            const cell = elem("td", "empty", table.dataset.emptyMessage || "No builds recorded yet.");
            cell.colSpan = 7;
            tbody.appendChild(elem("tr")).appendChild(cell);
            return;
        }
        builds.forEach((build) => tbody.appendChild(renderBuildRow(build, allowRestart, restartAtOriginHead)));
    }

    startPolling(refresh, TABLE_POLL_MS);
}

// ---- current builds with live logs ------------------------------------------

function renderBuildCard(build) {
    const card = elem("section", "build-card");
    card.dataset.artifactKey = build.artifactKey;
    card.dataset.startedAt = build.startedAt || "";
    card.dataset.runningSince = build.runningSince || "";
    card.dataset.status = build.status || "running";

    const header = elem("header", "build-card-header");
    header.appendChild(statusBadge(build.status || "running"));
    const branchTools = elem("span", "branch link-tools");
    const displayName = build.name || build.branch;
    branchTools.appendChild(
        giteaRepoUrl
            ? externalLink(giteaRepoUrl + "/src/branch/" + encodeBranchPath(build.branch), displayName)
            : elem("span", null, displayName),
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
    header.appendChild(
        elem("span", durationCellClass(build.status) + " running-duration", formatDuration(displayDurationSeconds(build))),
    );
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
        const url = `${apiBase()}/builds/current/${encodeURIComponent(build.artifactKey)}/log?offset=${offset}`;
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
                card.dataset.runningSince = build.runningSince || card.dataset.runningSince;
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

// ---- system metrics ----------------------------------------------------------

/** The total each utilization metric is compared against for the critical highlighting. */
const UTILIZATION_TOTALS = { cpuUsed: "cpuCount", ramUsedGib: "ramTotalGib", diskUsedGib: "diskTotalGib" };

/** Same thresholds as UiFormats.utilizationClass: warn from 80% of the total, critical from 90%. */
function utilizationClass(used, total) {
    if (used == null || total == null || !(total > 0)) {
        return "";
    }
    const ratio = used / total;
    if (ratio >= 0.90) {
        return "metric-crit";
    }
    if (ratio >= 0.80) {
        return "metric-warn";
    }
    return "";
}

/** The metric rows are fixed, so only the cell texts are updated — never rebuilt. */
function initSystemTable() {
    const table = document.getElementById("system-table");
    if (!table) {
        return;
    }

    function setText(id, text) {
        const element = document.getElementById(id);
        if (element) {
            element.textContent = text;
        }
    }

    async function refresh() {
        const metrics = await fetchJson(table.dataset.api);
        table.querySelectorAll("tbody tr[data-metric]").forEach((row) => {
            const aggregate = metrics[row.dataset.metric];
            row.querySelectorAll("[data-field]").forEach((cell) => {
                cell.textContent = formatMetric(aggregate ? aggregate[cell.dataset.field] : null);
            });
            const currentCell = row.querySelector('[data-field="current"]');
            if (currentCell) {
                const totalField = UTILIZATION_TOTALS[row.dataset.metric];
                const cssClass = totalField
                    ? utilizationClass(aggregate ? aggregate.current : null, metrics[totalField])
                    : "";
                currentCell.classList.toggle("metric-warn", cssClass === "metric-warn");
                currentCell.classList.toggle("metric-crit", cssClass === "metric-crit");
            }
        });
        setText("info-cpu-count", metrics.cpuCount != null ? metrics.cpuCount + " cores" : "n/a");
        setText("info-ram-total", metrics.ramTotalGib != null ? formatMetric(metrics.ramTotalGib) + " GiB" : "n/a");
        setText("info-disk-total", metrics.diskTotalGib != null ? formatMetric(metrics.diskTotalGib) + " GiB" : "n/a");
        setText("info-updated", formatTimeOfDay(metrics.timestamp));
    }

    startPolling(refresh, SYSTEM_POLL_MS);
}

// ---- running-duration ticking ------------------------------------------------

function tickRunningDurations() {
    document.querySelectorAll("[data-started-at]").forEach((element) => {
        const status = element.dataset.status;
        if (status !== "running" && status !== "pending") {
            return;
        }
        // running: live build time since leaving the queue; pending: wait time (italic)
        const basisIso = status === "running"
            ? (element.dataset.runningSince || element.dataset.startedAt)
            : element.dataset.startedAt;
        const elapsed = elapsedSeconds(basisIso);
        const durationCell = element.querySelector(".duration-cell");
        if (durationCell && elapsed != null) {
            durationCell.textContent = formatDuration(elapsed);
            durationCell.classList.toggle("duration-wait", status === "pending");
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
            const atOriginHead = button.dataset.atOriginHead === "true" ? "&atOriginHead=true" : "";
            await sendAction(apiBase() + "/builds/restart?branch=" + encodeURIComponent(button.dataset.branch) + atOriginHead, "POST");
        } else if (action === "cancel") {
            await sendAction(`${apiBase()}/builds/${encodeURIComponent(button.dataset.artifactKey)}/cancel`, "POST");
        } else if (action === "delete") {
            await sendAction(apiBase() + "/builds/" + encodeURIComponent(button.dataset.artifactKey), "DELETE");
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

// ---- reload button -------------------------------------------------------------

/** Refreshes via the page's poller; pages without one (e.g. artifact index) reload fully. */
function initReloadButton() {
    const button = document.getElementById("reload-button");
    if (!button) {
        return;
    }
    button.addEventListener("animationend", () => button.classList.remove("is-reloading"));
    button.addEventListener("click", () => {
        button.classList.add("is-reloading");
        if (refreshNow) {
            refreshNow();
        } else {
            window.location.reload();
        }
    });
}

// ---- page wiring ---------------------------------------------------------------

initBuildsTable();
initCurrentBuilds();
initSystemTable();
initReloadButton();
// pages with a poller update the banner from their own tick; the static ones ask once
if (!refreshNow) {
    refreshWatcherBanner();
}
setInterval(tickRunningDurations, 1000);
tickRunningDurations();
