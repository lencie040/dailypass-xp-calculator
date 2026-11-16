'use strict';

const byId = id => document.getElementById(id);
const bridge = 'http://127.0.0.1:38767';
const stateLabel = { normal: '運行中', delay: '遅延', bigDelay: '大幅遅延', stopped: '運行停止', noService: '運行なし' };
const int = new Intl.NumberFormat('ja-JP', { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat('ja-JP', { maximumFractionDigits: 1 });
const kilometers = new Intl.NumberFormat('ja-JP', { maximumFractionDigits: 3 });
const local = location.origin === bridge;
const loadButton = byId('load');
const status = byId('status');
const filters = [byId('allow'), byId('deny')];
let data = null;

function primaryLabel(text) {
    return text.split('|')[0].trim() || text.trim();
}

function routeLabel(text) {
    const [line, service] = text.split('||');
    return service ? primaryLabel(line) + ' · ' + primaryLabel(service) : primaryLabel(line);
}

function serviceAliases(text) {
    const aliases = text.split('|').map(name => name.trim()).filter(Boolean);
    return aliases.length ? aliases : ['種別なし'];
}

function filterName(text) {
    return text.normalize('NFKC').trim().toLowerCase();
}

function filterSet(text) {
    return new Set(text.normalize('NFKC').split(/[,、;\r\n]+/u).map(filterName).filter(Boolean));
}

function filteredRoutes(routes, allow, deny, operating) {
    return routes.filter(route => {
        if (operating && !['normal', 'delay', 'bigDelay'].includes(route.status)) return false;
        const aliases = serviceAliases(route.number).map(filterName);
        return (allow.size === 0 || aliases.some(name => allow.has(name))) && !aliases.some(name => deny.has(name));
    });
}

function rateFor(route, options) {
    const timing = route[options.timing];
    const seconds = timing.seconds > 0 ? timing.seconds : null;
    const companyMeters = Object.entries(route.companyMeters);
    const needsEstimate = companyMeters.length === 0;
    let xpMeters = null;
    let fareEstimate = false;
    if (options.company) {
        xpMeters = Object.hasOwn(route.companyMeters, options.company) ? route.companyMeters[options.company] : null;
    } else if (!needsEstimate) {
        xpMeters = companyMeters.reduce((sum, [, meters]) => sum + meters, 0);
    } else if (options.allowEstimate && route.meters > 0) {
        xpMeters = route.meters;
        fareEstimate = true;
    }

    const xp = xpMeters === null ? null : Math.floor(xpMeters * (options.rate / 1000));
    const efficiency = xp !== null && seconds !== null ? xp * 3600 / (seconds + options.waitSeconds) : null;
    return { route, seconds, timing, xpMeters, xp, efficiency, fareEstimate };
}

function compareEfficiency(a, b) {
    return (b.efficiency ?? -1) - (a.efficiency ?? -1)
        || Number(a.fareEstimate) - Number(b.fareEstimate)
        || a.timing.estimatedSections - b.timing.estimatedSections
        || a.route.name.localeCompare(b.route.name, 'ja');
}

function bestByType(rows) {
    const best = new Map();
    for (const row of rows) {
        const kind = filterName(serviceAliases(row.route.number)[0]);
        if (!best.has(kind)) best.set(kind, row);
    }
    return [...best.values()];
}

function timeLabel(seconds) {
    if (seconds === null) return '—';
    const total = Math.round(seconds);
    const h = Math.floor(total / 3600);
    const m = Math.floor(total % 3600 / 60);
    const s = total % 60;
    return h ? h + '時間' + m + '分' : m ? m + '分' + s + '秒' : s + '秒';
}

function kmLabel(meters) {
    return meters !== null && meters >= 0 ? kilometers.format(meters / 1000) + ' km' : '—';
}

function cell(tr, text, className, subtext) {
    const td = tr.insertCell();
    td.textContent = text;
    if (className) td.className = className;
    if (subtext) {
        const small = document.createElement('small');
        small.textContent = subtext;
        td.append(small);
    }
}

function renderRow(tr, row) {
    const { route } = row;
    const statusText = stateLabel[route.status] || '運行状況不明';
    const mode = serviceAliases(route.number)[0];
    const labels = [mode, statusText];
    if (!route.available) labels.push('詳細未取得');
    cell(tr, routeLabel(route.name), 'route', labels.join(' · '));
    const from = primaryLabel(route.from);
    const to = primaryLabel(route.to);
    cell(tr, from && to ? from + ' → ' + to : from || primaryLabel(route.destination) || '—',
        'endpoints', route.stations > 0 ? route.stations + '駅' : '');
    cell(tr, kmLabel(route.meters), 'num', route.meters > 0 && !route.measured ? '距離推定' : '');
    cell(tr, timeLabel(row.seconds), 'num', row.timing.estimatedSections ? row.timing.estimatedSections + '区間推定' : '');
    cell(tr, kmLabel(row.xpMeters), 'num', row.fareEstimate ? '線路距離で概算' : '');
    cell(tr, row.xp === null ? '—' : int.format(row.xp), 'num');
    cell(tr, row.efficiency === null ? '—' : decimal.format(row.efficiency), 'num rate');
}

function numberControl(id, min, max) {
    const input = byId(id);
    const value = input.valueAsNumber;
    const valid = Number.isFinite(value) && value >= min && value <= max;
    if (valid) input.removeAttribute('aria-invalid');
    else input.setAttribute('aria-invalid', 'true');
    return valid ? value : null;
}

function render() {
    if (!data) return;

    const rate = numberControl('rate', 0.01, 1000);
    const wait = numberControl('wait', 0, 180);
    byId('numbers').hidden = rate !== null && wait !== null;
    if (rate === null || wait === null) {
        byId('best').hidden = true;
        byId('results').hidden = true;
        return;
    }

    const company = byId('company').value;
    const estimate = byId('allowEstimate');
    estimate.disabled = Boolean(company);
    const options = { company, rate, waitSeconds: wait * 60, timing: byId('timing').value,
        allowEstimate: !company && estimate.checked };
    const routes = filteredRoutes(data.routes, filterSet(filters[0].value), filterSet(filters[1].value), byId('operating').checked);
    const rows = routes.map(route => rateFor(route, options)).sort(compareEfficiency);
    const active = rows.filter(row => !company || row.xpMeters !== null);
    const leader = active.find(row => row.efficiency > 0);
    byId('best').hidden = !leader;
    if (leader) {
        byId('best-route').textContent = routeLabel(leader.route.name);
        byId('best-rate').textContent = decimal.format(leader.efficiency) + ' XP/時';
        const quality = leader.fareEstimate || leader.timing.estimatedSections > 0 || !leader.route.measured ? ' · 推定を含む' : '';
        const operating = stateLabel[leader.route.status] || '運行状況不明';
        byId('best-detail').textContent = serviceAliases(leader.route.number)[0] + ' · ' + int.format(leader.xp)
            + ' XP/乗車 · ' + timeLabel(leader.seconds) + ' · ' + operating + quality;
    }

    const grouped = byId('grouping').value === 'types';
    const display = grouped ? bestByType(active) : active;
    switch (byId('sort').value) {
        case 'time': display.sort((a, b) => (a.seconds ?? Infinity) - (b.seconds ?? Infinity) || compareEfficiency(a, b)); break;
        case 'xp': display.sort((a, b) => (b.xp ?? -1) - (a.xp ?? -1) || compareEfficiency(a, b)); break;
    }
    byId('results-title').textContent = grouped ? '種別ごとのベスト' : '運行パターン';
    byId('count').textContent = int.format(display.length) + (grouped ? '種別' : ' / ' + int.format(data.routes.length) + '便');
    byId('empty').hidden = display.length > 0;
    byId('table').hidden = display.length === 0;
    const fragment = document.createDocumentFragment();
    for (const row of display) {
        const tr = document.createElement('tr');
        renderRow(tr, row);
        fragment.append(tr);
    }
    byId('rows').replaceChildren(fragment);
    byId('results').hidden = false;
}

function installData(value) {
    const firstLoad = data === null;
    data = value;
    const company = byId('company');
    const selected = company.value;
    company.replaceChildren(new Option('総合', ''));
    for (const [id, name] of Object.entries(value.companies).sort((a, b) => a[1].localeCompare(b[1], 'ja'))) {
        company.add(new Option(name, id));
    }
    if (Object.hasOwn(value.companies, selected)) company.value = selected;
    if (firstLoad && !value.distanceFare) byId('allowEstimate').checked = true;

    const names = [...new Set(value.routes.map(route => serviceAliases(route.number)[0]))];
    byId('types').textContent = '取得した種別 ' + names.sort((a, b) => a.localeCompare(b, 'ja')).join('、');
    byId('settings').hidden = false;
    status.textContent = int.format(value.routes.length) + '便を取得しました。'
        + (value.distanceFare ? '' : ' DailyPassの距離精算設定が使えないため、線路距離で概算できます。');
    render();
}

async function loadRoutes() {
    if (loadButton.disabled) return;
    loadButton.disabled = true;
    status.textContent = 'MTROPSの路線と駅間時間を読み込んでいます…';
    try {
        const response = await fetch(bridge + '/api/mtrops/routes', {
            targetAddressSpace: 'loopback', signal: AbortSignal.timeout(25_000), cache: 'no-store'
        });
        if (response.status === 404) {
            status.textContent = '路線ランキングには連携MOD 1.1.0が必要です。';
            return;
        }
        if (!response.ok) throw new Error('Local bridge unavailable');
        const value = await response.json();
        if (value.state === 'stale') {
            status.textContent = 'MTROPSのゲーム接続を待っています。サーバーに入ったあと再読み込みしてください。';
        } else if (value.state === 'ready') {
            installData(value);
        } else {
            status.textContent = 'MTROPSの localhost:8765 に接続できません。MTROPSを起動して再読み込みしてください。';
        }
    } catch {
        status.textContent = '連携MODとMinecraftを起動し、ブラウザのローカルネットワークアクセスを許可してください。';
    } finally {
        if (data) loadButton.textContent = '最新データを読み込む';
        loadButton.disabled = false;
    }
}

for (const field of filters) {
    const key = 'dailypass-mtrops-' + field.id;
    try { field.value = localStorage.getItem(key) ?? ''; } catch { /* Storage is optional. */ }
    field.addEventListener('input', () => {
        try { localStorage.setItem(key, field.value); } catch { /* Storage is optional. */ }
    });
}

loadButton.addEventListener('click', loadRoutes);
byId('settings').addEventListener('input', render);
if (local) {
    void loadRoutes();
} else if (navigator.permissions?.query) {
    navigator.permissions.query({ name: 'loopback-network' }).then(permission => {
        if (permission.state === 'granted') void loadRoutes();
    }).catch(() => {});
}
