// shared.jsx — Mock data + common bits used across all three direction files.

const STATS = {
  totalHours: 187,
  totalMinutes: 24,
  totalAllTimeHours: 612,
  savedHours: 31,
  savedMinutes: 12,
  savedBreakdown: { speed: 22.4, silence: 6.1, intros: 2.7 }, // hours
  episodesPlayed: 412,
  episodesCompleted: 287,
  episodesInProgress: 125,
  streakDays: 47,
  bestDay: 'Tuesday',
  topHourLocal: 8,
  // Top subscriptions — fictional names so we are not riffing on real podcast brands.
  shows: [
    { id: 's1', title: 'Field Notes Weekly',  host: 'Mara Velez',          hrs: 38.4, color: '#f4a261', pct: 21 },
    { id: 's2', title: 'The Decoder Hour',    host: 'Eli Park & Sam Reyes', hrs: 27.1, color: '#2a9d8f', pct: 15 },
    { id: 's3', title: 'Margin Notes',        host: 'June Okafor',         hrs: 22.6, color: '#e76f51', pct: 12 },
    { id: 's4', title: 'Slow Radio',          host: 'Kit Hannan',          hrs: 19.8, color: '#264653', pct: 11 },
    { id: 's5', title: 'Atlas of Tomorrow',   host: 'Dr. Lin Watanabe',    hrs: 14.2, color: '#a06cd5', pct: 8 },
    { id: 's6', title: 'Late Night Build',    host: 'Ada & Idris',         hrs: 11.0, color: '#83c5be', pct: 6 },
    { id: 's7', title: 'After the Fact',      host: 'Caro Mendes',         hrs:  9.3, color: '#bc4749', pct: 5 },
    { id: 's8', title: 'Quiet Engineering',   host: 'Sho Tanaka',          hrs:  7.6, color: '#588157', pct: 4 },
    { id: 's9', title: 'Other',               host: '23 more shows',       hrs: 37.4, color: '#9aa0a6', pct: 18 },
  ],
  yearly: [
    { year: 2021, hrs:  74 },
    { year: 2022, hrs: 121 },
    { year: 2023, hrs: 158 },
    { year: 2024, hrs: 142 },
    { year: 2025, hrs: 187 },
    { year: 2026, hrs:  47 },
  ],
  // 24 values, listening minutes by hour-of-day (local time)
  byHour: [2, 1, 0, 0, 0, 1, 8, 22, 38, 31, 18, 12, 25, 28, 19, 14, 21, 30, 24, 17, 12, 9, 6, 4],
  // 7 values Sun..Sat
  byDay: [22, 41, 58, 47, 39, 28, 19],
  // 12 weeks of listening, sparkline-friendly
  weekly: [3.2, 4.1, 5.6, 5.0, 7.2, 6.5, 8.1, 9.0, 7.7, 8.4, 9.6, 11.1],
  // Calendar heatmap: 7 rows × 26 weeks (half year). 0..4 intensity buckets.
  heatmap: (() => {
    const out = [];
    let seed = 9;
    for (let w = 0; w < 26; w++) {
      const col = [];
      for (let d = 0; d < 7; d++) {
        seed = (seed * 9301 + 49297) % 233280;
        const r = seed / 233280;
        const weekend = d === 0 || d === 6;
        const v = r < 0.18 ? 0 : r < 0.45 ? 1 : r < 0.72 ? 2 : r < 0.92 ? 3 : 4;
        col.push(weekend ? Math.max(0, v - 1) : v);
      }
      out.push(col);
    }
    return out;
  })(),
};

// Fictional feed for the per-feed dialog
const FEED = {
  title: 'The Decoder Hour',
  host: 'Eli Park & Sam Reyes',
  color: '#2a9d8f',
  subscribed: 'May 2024',
  episodesTotal: 142,
  episodesPlayed: 86,
  hrsListened: 27.1,
  hrsSaved: 4.8,
  avgEpisodeMin: 52,
  weekly: [0.4, 0.6, 1.1, 0.8, 0.9, 1.4, 1.2, 1.0, 1.6, 1.3, 1.8, 2.1],
};

// Tiny inline icons (stroke-based, monochrome) — used by all directions.
function Icon({ name, size = 22, color = 'currentColor', stroke = 1.8 }) {
  const p = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth: stroke, strokeLinecap: 'round', strokeLinejoin: 'round' };
  switch (name) {
    case 'back':    return <svg {...p}><path d="M15 5l-7 7 7 7"/></svg>;
    case 'menu':    return <svg {...p}><circle cx="12" cy="5" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="12" cy="19" r="1.2"/></svg>;
    case 'filter':  return <svg {...p}><path d="M4 6h16M7 12h10M10 18h4"/></svg>;
    case 'share':   return <svg {...p}><circle cx="6" cy="12" r="2.2"/><circle cx="18" cy="6" r="2.2"/><circle cx="18" cy="18" r="2.2"/><path d="M8 11l8-4M8 13l8 4"/></svg>;
    case 'play':    return <svg {...p}><path d="M8 5l11 7-11 7z" fill={color}/></svg>;
    case 'spark':   return <svg {...p}><path d="M12 3l2.4 5.4 5.6.8-4 4 1 5.8L12 16.4 6.9 19l1-5.8-4-4 5.6-.8z"/></svg>;
    case 'clock':   return <svg {...p}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>;
    case 'fire':    return <svg {...p}><path d="M12 3c1 4 5 5 5 10a5 5 0 01-10 0c0-2 1-3 2-4 0 2 1 3 2 3-1-3 0-6 1-9z"/></svg>;
    case 'check':   return <svg {...p}><path d="M5 12l5 5 9-12"/></svg>;
    case 'chevron': return <svg {...p}><path d="M9 6l6 6-6 6"/></svg>;
    case 'close':   return <svg {...p}><path d="M6 6l12 12M18 6L6 18"/></svg>;
    case 'mic':     return <svg {...p}><rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0014 0M12 18v3"/></svg>;
    case 'cal':     return <svg {...p}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>;
    case 'trim':    return <svg {...p}><circle cx="6" cy="7" r="2.5"/><circle cx="6" cy="17" r="2.5"/><path d="M8 8l12 8M8 16l12-8"/></svg>;
    default:        return null;
  }
}

// Format helpers
const fmtHM = (h, m = 0) => `${h}h ${String(m).padStart(2, '0')}m`;
const fmtH  = (h) => `${h.toFixed(1)}h`;

// A subtle striped placeholder (used very sparingly — we lean on real data).
function StripePlaceholder({ width = 80, height = 80, label = '', color = '#bbb' }) {
  const id = `sp-${Math.random().toString(36).slice(2, 8)}`;
  return (
    <svg width={width} height={height} style={{ display: 'block', borderRadius: 8 }}>
      <defs>
        <pattern id={id} patternUnits="userSpaceOnUse" width="8" height="8" patternTransform="rotate(45)">
          <rect width="8" height="8" fill={color} opacity="0.15"/>
          <rect width="3" height="8" fill={color} opacity="0.3"/>
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill={`url(#${id})`} rx="8"/>
      {label && <text x="50%" y="52%" fontSize="9" fontFamily="ui-monospace, monospace" fill={color} opacity="0.85" textAnchor="middle">{label}</text>}
    </svg>
  );
}

// Cover-art placeholder (colored square with initials).
function Cover({ color, title, size = 40, radius = 8 }) {
  const initials = title.split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase();
  return (
    <div style={{
      width: size, height: size, borderRadius: radius, background: color,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: 'rgba(255,255,255,0.92)', fontWeight: 700, fontSize: size * 0.34,
      letterSpacing: -0.5, flexShrink: 0,
    }}>{initials}</div>
  );
}

Object.assign(window, { STATS, FEED, Icon, fmtHM, fmtH, StripePlaceholder, Cover });
