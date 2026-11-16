'use strict';

const byId = id => document.getElementById(id);
const bridge = 'http://127.0.0.1:38767';
const pageSize = 25;
const integer = new Intl.NumberFormat('ja-JP');
const decimal = new Intl.NumberFormat('ja-JP', { maximumFractionDigits: 1 });
const clock = new Intl.DateTimeFormat('ja-JP', { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
const status = byId('status');
const loadButton = byId('load');
const buffer = byId('buffer');
const timeMode = byId('time-mode');
const costMode = byId('cost-mode');
let sortByCost = false;
let hasFares = false;
let data, ranked = [], page = 0;

function primaryLabel(text) {
    return text.split('|')[0].trim() || text.trim();
}

function routeLabel(ride) {
    const [route, service] = ride.routeName.split('||');
    return [primaryLabel(route), primaryLabel(ride.type || service || '')].filter(Boolean).join(' · ');
}

function filterName(text) {
    return text.normalize('NFKC').trim().toLowerCase();
}

function filterSet(text) {
    return new Set(text.normalize('NFKC').split(/[,、;\r\n]+/u).map(filterName).filter(Boolean));
}

function serviceNames(ride) {
    const names = ride.type.split('|').map(name => name.trim()).filter(Boolean);
    return names.length ? names : ['種別なし'];
}

function passesType(ride, allow, deny) {
    const names = serviceNames(ride).map(filterName);
    return (!allow.size || names.some(name => allow.has(name))) && !names.some(name => deny.has(name));
}

function rideXp(ride, company, rate, canEstimate) {
    let meters = ride.companyMeters[company] ?? 0;
    let estimated = false;
    if (!company) {
        const distances = Object.values(ride.companyMeters);
        if (distances.length) meters = distances.reduce((sum, distance) => sum + distance, 0);
        else if (canEstimate) {
            meters = ride.meters;
            estimated = true;
        } else return null;
    }
    return { xp: Math.floor(meters * (rate / 1000)), estimated };
}

function duration(seconds) {
    const total = Math.round(seconds);
    const minutes = Math.floor(total / 60);
    return minutes ? minutes + '分' + total % 60 + '秒' : total + '秒';
}

function cell(tr, content, className, notes = []) {
    const td = tr.insertCell();
    td.className = className;
    td.textContent = content;
    for (const line of notes.filter(Boolean)) {
        const small = document.createElement('small');
        small.textContent = line;
        td.append(small);
    }
}

function stationLabels(loop) {
    return [loop.start, loop.turn, loop.start].map(primaryLabel).join(' → ');
}

function roundTripFare(loop) {
    const out = loop.outbound.regularFareCp;
    const back = loop.inbound.regularFareCp;
    return Number.isInteger(out) && out >= 0 && Number.isInteger(back) && back >= 0 ? out + back : null;
}

function setRankingMode(byCost) {
    sortByCost = byCost && hasFares;
    timeMode.setAttribute('aria-pressed', !sortByCost);
    costMode.setAttribute('aria-pressed', sortByCost);
    byId('time-column').setAttribute('aria-sort', sortByCost ? 'none' : 'descending');
    byId('cost-column').setAttribute('aria-sort', sortByCost ? 'descending' : 'none');
    rankLoops();
}

function renderPage() {
    const pages = Math.ceil(ranked.length / pageSize);
    const first = page * pageSize;
    const fragment = document.createDocumentFragment();
    ranked.slice(first, first + pageSize).forEach((row, index) => {
        const { loop, outbound, inbound, xp, efficiency, fareCp, xpPer100Cp } = row;
        const { cycle } = loop;
        const tr = document.createElement('tr');
        const depots = loop.sharedDepots.map(primaryLabel).join('、');
        const turns = cycle.sameTrainTurns ? '同じ編成で ' + cycle.sameTrainTurns + '回' : '';
        const distanceEstimate = outbound.estimated || inbound.estimated ? '線路距離で概算' : '';
        cell(tr, integer.format(first + index + 1), 'num');
        cell(tr, stationLabels(loop), 'trip', [
            '往路 ' + routeLabel(loop.outbound),
            '復路 ' + routeLabel(loop.inbound),
            [depots, turns].filter(Boolean).join(' · ')
        ]);
        cell(tr, clock.format(cycle.firstDeparture), 'num', ['取得時点から ' + duration((cycle.firstDeparture - data.measuredAt) / 1000)]);
        cell(tr, duration(cycle.waitAtDestination), 'num');
        cell(tr, duration(cycle.waitAtOrigin), 'num');
        cell(tr, duration(cycle.seconds), 'num', [
            cycle.cycles + '周の平均',
            '乗車 ' + duration(cycle.outboundSeconds + cycle.returnSeconds),
            cycle.estimatedRides ? cycle.estimatedRides + '便の到着を推定' : ''
        ]);
        cell(tr, integer.format(xp), 'num', [outbound.xp + ' ＋ ' + inbound.xp, distanceEstimate]);
        cell(tr, decimal.format(efficiency), sortByCost ? 'num' : 'num rate');
        if (hasFares) {
            cell(tr, fareCp === null ? '不明' : integer.format(fareCp) + ' CP', 'num',
                fareCp === null ? [] : [loop.outbound.regularFareCp + ' ＋ ' + loop.inbound.regularFareCp]);
            const costLabel = fareCp === 0 ? '0 CPで獲得' : xpPer100Cp === null ? '—' : decimal.format(xpPer100Cp);
            cell(tr, costLabel, sortByCost ? 'num rate' : 'num');
        }
        fragment.append(tr);
    });
    byId('rows').replaceChildren(fragment);
    byId('pagination').hidden = pages <= 1;
    byId('page').textContent = integer.format(first + 1) + '〜' + integer.format(Math.min(first + pageSize, ranked.length))
        + ' / ' + integer.format(ranked.length) + '件';
    byId('prev').disabled = page === 0;
    byId('next').disabled = page + 1 >= pages;
}

function rankLoops() {
    if (!data) return;
    const rate = byId('rate');
    byId('input-error').hidden = rate.validity.valid;
    byId('results').hidden = !rate.validity.valid;
    byId('leader').hidden = true;
    if (!rate.validity.valid) return;

    const company = byId('company').value;
    const allow = filterSet(byId('allow').value);
    const deny = filterSet(byId('deny').value);
    const station = filterName(byId('station').value);
    const shared = data.depotsAvailable && byId('shared').checked;
    const estimate = byId('estimate');
    estimate.disabled = Boolean(company);
    ranked = [];
    for (const loop of data.loops) {
        if (shared && !loop.sharedDepots.length) continue;
        if (station && ![loop.start, loop.turn].some(name => filterName(name).includes(station))) continue;
        if (!passesType(loop.outbound, allow, deny) || !passesType(loop.inbound, allow, deny)) continue;
        const fareCp = roundTripFare(loop);
        if (sortByCost && fareCp === null) continue;
        const outbound = rideXp(loop.outbound, company, rate.valueAsNumber, estimate.checked);
        const inbound = rideXp(loop.inbound, company, rate.valueAsNumber, estimate.checked);
        if (outbound === null || inbound === null) continue;
        const xp = outbound.xp + inbound.xp;
        if (xp > 0) {
            ranked.push({ loop, outbound, inbound, xp, efficiency: xp * 3600 / loop.cycle.seconds,
                fareCp, xpPer100Cp: fareCp > 0 ? xp * 100 / fareCp : null });
        }
    }
    ranked.sort((a, b) => (sortByCost
        ? Number(b.fareCp === 0) - Number(a.fareCp === 0) || (b.xpPer100Cp ?? 0) - (a.xpPer100Cp ?? 0)
        : 0)
        || b.efficiency - a.efficiency || b.loop.cycle.cycles - a.loop.cycle.cycles
        || a.loop.cycle.estimatedRides - b.loop.cycle.estimatedRides || a.loop.cycle.seconds - b.loop.cycle.seconds
        || a.loop.cycle.firstDeparture - b.loop.cycle.firstDeparture);
    page = 0;
    const first = ranked[0];
    if (first) {
        byId('leader-stations').textContent = stationLabels(first.loop);
        byId('leader-detail').textContent = integer.format(first.xp) + ' XP/周 · ' + duration(first.loop.cycle.seconds)
            + '/周 · ' + clock.format(first.loop.cycle.firstDeparture) + ' 発'
            + (sortByCost ? ' · ' + integer.format(first.fareCp) + ' CP/周 · ' + decimal.format(first.efficiency) + ' XP/時' : '')
            + (first.loop.cycle.estimatedRides || first.outbound.estimated || first.inbound.estimated ? ' · 推定を含む' : '');
        byId('leader-rate').textContent = sortByCost
            ? (first.fareCp === 0 ? '0 CPで獲得' : decimal.format(first.xpPer100Cp) + ' XP/100 CP')
            : decimal.format(first.efficiency) + ' XP/時';
        byId('leader').hidden = false;
    }
    byId('count').textContent = integer.format(ranked.length) + ' / ' + integer.format(data.loops.length) + '件';
    byId('empty').textContent = sortByCost
        ? 'この条件で往復の普通運賃とXPが分かる候補がありません。'
        : '条件に合う往復がありません。種別・駅名・車庫の条件を見直せます。';
    byId('empty').hidden = Boolean(first);
    byId('table').hidden = !first;
    renderPage();
}

function installData(value, transferSeconds) {
    if (!data && !value.distanceFare) byId('estimate').checked = true;
    data = value;
    const company = byId('company');
    const selected = company.value;
    company.replaceChildren(new Option('総合', ''));
    for (const [id, name] of Object.entries(value.companies).sort((a, b) => a[1].localeCompare(b[1], 'ja'))) {
        company.add(new Option(name, id));
    }
    if (Object.hasOwn(value.companies, selected)) company.value = selected;
    const types = new Set();
    for (const { outbound, inbound } of value.loops) {
        types.add(serviceNames(outbound)[0]);
        types.add(serviceNames(inbound)[0]);
    }
    byId('types').textContent = types.size ? '取得した種別 ' + [...types].sort((a, b) => a.localeCompare(b, 'ja')).join('、') : '';
    byId('shared').disabled = !value.depotsAvailable;
    hasFares = value.loops.some(loop => roundTripFare(loop) !== null);
    costMode.disabled = !hasFares;
    byId('fare-column').hidden = !hasFares;
    byId('cost-column').hidden = !hasFares;
    byId('cost-note').textContent = hasFares ? 'コスパ重視は往復の普通運賃100 CPあたりのXP順です。'
        : 'コスパ重視には、連携MOD 1.2.1とDailyPassの普通運賃データが必要です。';
    byId('settings').hidden = false;
    loadButton.textContent = '時刻表を更新';
    const messages = [
        clock.format(value.measuredAt) + ' 取得 · ' + integer.format(value.schedules) + '駅 · 乗り換え ' + transferSeconds + '秒',
        value.schedules < value.stations ? '一部の駅の時刻表を取得できませんでした。' : '',
        value.depotsAvailable ? '' : '車庫一覧を取得できなかったため、時刻表から組める往復を表示します。',
        value.distanceFare ? '' : 'DailyPassの距離精算設定を取得できなかったため、線路距離で概算できます。'
    ];
    status.textContent = messages.filter(Boolean).join(' ');
    setRankingMode(sortByCost);
}

async function loadLoops() {
    if (loadButton.disabled || !buffer.reportValidity()) return;
    const transferSeconds = buffer.valueAsNumber;
    loadButton.disabled = true;
    buffer.disabled = true;
    status.textContent = 'MTROPSの車庫一覧と駅時刻表を読み込んでいます…';
    try {
        const response = await fetch(bridge + '/api/mtrops/loops?transfer=' + transferSeconds, {
            targetAddressSpace: 'loopback', signal: AbortSignal.timeout(45_000), cache: 'no-store'
        });
        if (response.status === 404) {
            status.textContent = '折り返しランキングには連携MOD 1.2.0が必要です。';
            return;
        }
        if (!response.ok) throw new Error('Local bridge unavailable');
        const value = await response.json();
        if (value.state === 'ready') installData(value, transferSeconds);
        else if (value.state === 'noSchedule') {
            status.textContent = 'MTROPSから駅の時刻表がまだ届いていません。ゲームに入ったあとで更新してください。';
        } else if (value.state === 'stale') {
            status.textContent = 'MTROPSのゲーム接続を待っています。ゲームに入ったあとで更新してください。';
        } else {
            status.textContent = 'MTROPSの localhost:8765 に接続できません。MTROPSを起動して更新してください。';
        }
    } catch {
        status.textContent = '連携MODとMinecraftを起動し、ブラウザのローカルネットワークアクセスを許可してください。';
    } finally {
        if (data) {
            if (!status.textContent.startsWith(clock.format(data.measuredAt))) {
                status.textContent += ' 表には ' + clock.format(data.measuredAt) + ' に取得した結果を表示しています。';
            }
        }
        loadButton.disabled = false;
        buffer.disabled = false;
    }
}

for (const id of ['allow', 'deny']) {
    const field = byId(id);
    const key = 'dailypass-mtrops-' + id;
    try { field.value = localStorage.getItem(key) ?? ''; } catch { /* Storage is optional. */ }
    field.addEventListener('input', () => {
        try { localStorage.setItem(key, field.value); } catch { /* Storage is optional. */ }
    });
}
byId('settings').addEventListener('input', rankLoops);
timeMode.addEventListener('click', () => setRankingMode(false));
costMode.addEventListener('click', () => setRankingMode(true));
byId('connection').addEventListener('submit', event => {
    event.preventDefault();
    void loadLoops();
});
byId('prev').addEventListener('click', () => { page--; renderPage(); });
byId('next').addEventListener('click', () => { page++; renderPage(); });
if (location.origin === bridge) void loadLoops();
else if (navigator.permissions?.query) {
    navigator.permissions.query({ name: 'loopback-network' }).then(permission => {
        if (permission.state === 'granted') void loadLoops();
    }).catch(() => {});
}
