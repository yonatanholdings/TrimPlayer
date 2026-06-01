// final.jsx — Polished editorial-system stats. Five screens.
// Subscriptions adopts the Calm screen's data-viz (donut + per-show bar list
// with cover art), but in the editorial cream/serif palette so it sits
// inside the same publication.

const E = {
  bg: '#f4ede0',
  paper: '#fbf8f1',
  paperLift: '#ffffff',
  ink: '#15110d',
  inkSoft: '#3a322a',
  inkMute: '#7a6e60',
  rule: '#15110d',
  faint: 'rgba(21,17,13,0.10)',
  veryFaint: 'rgba(21,17,13,0.05)',
  accent: '#b8442e',     // vermilion
  accentSoft: '#e8b9a4',
  accentTint: '#f3d6c4',
  gold: '#a47436',
  goldSoft: '#e1c79a',
  serif: "'Instrument Serif', 'Cormorant Garamond', Georgia, serif",
  sans: "'IBM Plex Sans', system-ui, sans-serif",
  mono: "'IBM Plex Mono', ui-monospace, monospace",
};

// ─────────────────────────────────────────────────────────
// Atoms
// ─────────────────────────────────────────────────────────

function Mast({ vol, page, dateline, kicker, title, lede, drop }) {
  return (
    <div style={{ background: E.bg, paddingTop: 4 }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        padding: '8px 24px 10px',
        fontFamily: E.mono, fontSize: 9, letterSpacing: 2.4, textTransform: 'uppercase', color: E.inkMute,
        borderBottom: `0.5px solid ${E.faint}`,
      }}>
        <span style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <span style={{ width: 5, height: 5, background: E.accent, borderRadius: '50%' }}/>
          Trim Player Statistics
        </span>
        <span>{vol} · {page}</span>
      </div>

      <div style={{ padding: '10px 24px 4px' }}>
        <div style={{ fontFamily: E.mono, fontSize: 10, letterSpacing: 1.6, textTransform: 'uppercase', color: E.accent, fontWeight: 500 }}>{kicker}</div>
        <h1 style={{ fontFamily: E.serif, fontSize: 52, fontWeight: 400, color: E.ink, lineHeight: 0.94, letterSpacing: -1.2, margin: '6px 0 0' }}>{title}</h1>
        {lede && (
          <p style={{ fontFamily: E.sans, fontSize: 13, lineHeight: 1.55, color: E.inkSoft, marginTop: 14, maxWidth: 320, textWrap: 'pretty' }}>
            {drop && <span style={{ float: 'left', fontFamily: E.serif, fontSize: 56, lineHeight: 0.78, paddingRight: 10, paddingTop: 6, color: E.accent }}>{drop}</span>}
            {lede}
          </p>
        )}
        {dateline && (
          <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 1.6, textTransform: 'uppercase', color: E.inkMute, marginTop: 14 }}>{dateline}</div>
        )}
      </div>
    </div>
  );
}

function Tabs({ items, active }) {
  return (
    <div style={{
      display: 'flex', gap: 0, padding: '0 24px',
      borderTop: `1px solid ${E.rule}`, borderBottom: `1px solid ${E.faint}`,
      background: E.bg,
    }}>
      {items.map((it, i) => (
        <div key={i} style={{
          padding: '12px 14px 10px', fontFamily: E.mono, fontSize: 10, letterSpacing: 1.6, textTransform: 'uppercase',
          color: i === active ? E.ink : E.inkMute,
          borderRight: i < items.length - 1 ? `1px solid ${E.faint}` : 'none',
          borderBottom: i === active ? `2px solid ${E.accent}` : '2px solid transparent',
          fontWeight: i === active ? 600 : 400,
          marginBottom: -1,
        }}>{it}</div>
      ))}
    </div>
  );
}

function Rule({ thick = false, dotted = false, m = 0 }) {
  if (dotted) return (
    <div style={{ margin: `0 24px`, borderTop: `1px dashed ${E.faint}`, marginTop: m, marginBottom: m }}/>
  );
  return <div style={{ height: thick ? 1.5 : 0.5, background: E.rule, opacity: thick ? 1 : 0.16, margin: `${m}px 24px` }}/>;
}

function SectionLabel({ n, children, right }) {
  return (
    <div style={{ padding: '18px 24px 10px', display: 'flex', alignItems: 'baseline', gap: 8 }}>
      {n && <span style={{ fontFamily: E.mono, fontSize: 10, letterSpacing: 2, color: E.inkMute }}>§ {n}</span>}
      <span style={{ fontFamily: E.mono, fontSize: 10, letterSpacing: 1.8, textTransform: 'uppercase', color: E.ink, fontWeight: 600 }}>{children}</span>
      <span style={{ flex: 1, height: 1, background: E.faint, alignSelf: 'center', marginLeft: 6 }}/>
      {right && <span style={{ fontFamily: E.mono, fontSize: 10, color: E.inkMute, letterSpacing: 1.4 }}>{right}</span>}
    </div>
  );
}

function SerifNum({ value, unit, size = 64, italic = true }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: 4 }}>
      <span style={{ fontFamily: E.serif, fontSize: size, fontWeight: 400, color: E.ink, letterSpacing: -size * 0.025, lineHeight: 0.92 }}>{value}</span>
      {unit && <span style={{ fontFamily: E.serif, fontSize: size * 0.36, fontStyle: italic ? 'italic' : 'normal', color: E.accent, marginLeft: 2 }}>{unit}</span>}
    </span>
  );
}

// Cover art initials, editorial-toned
function ECover({ color, title, size = 38 }) {
  const initials = title.split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase();
  return (
    <div style={{
      width: size, height: size, borderRadius: 4, background: color,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: 'rgba(255,255,255,0.95)', fontFamily: E.serif, fontWeight: 400, fontSize: size * 0.5,
      letterSpacing: -0.5, flexShrink: 0,
      boxShadow: 'inset 0 0 0 0.5px rgba(0,0,0,0.18)',
    }}>{initials}</div>
  );
}

// Annotation arrow + label, like newspaper marginalia
function Annot({ text, side = 'right', style = {} }) {
  const arrow = side === 'right' ? '←' : '→';
  return (
    <div style={{
      fontFamily: E.mono, fontSize: 9, letterSpacing: 1.4, textTransform: 'uppercase',
      color: E.inkMute, display: 'flex', alignItems: 'center', gap: 5, ...style,
    }}>
      {side === 'right' && <span>{arrow}</span>}
      <span>{text}</span>
      {side === 'left' && <span>{arrow}</span>}
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// 1 · Overview — front page of the issue
// ─────────────────────────────────────────────────────────

function Overview() {
  return (
    <div style={{ background: E.bg, fontFamily: E.sans, color: E.ink, paddingBottom: 36, minHeight: '100%' }}>
      <Mast
        vol="Vol. 06" page="Issue 02"
        kicker="The Year So Far · 2026"
        title={<>A reader’s<br/><i style={{ fontStyle: 'italic', color: E.accent }}>log.</i></>}
      />

      <div style={{ padding: '8px 24px 0' }}>
        <p style={{ fontFamily: E.sans, fontSize: 13, lineHeight: 1.55, color: E.inkSoft, margin: 0 }}>
          <span style={{ float: 'left', fontFamily: E.serif, fontSize: 64, lineHeight: 0.74, paddingRight: 10, paddingTop: 8, color: E.accent }}>F</span>
          rom January through May, you spent the equivalent of a working week and a half listening — across 38 podcasts, finishing seven in ten of the episodes you started. A correspondent’s record below.
        </p>
      </div>

      {/* Hero stat */}
      <div style={{ padding: '24px 24px 8px', display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16 }}>
        <div>
          <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 2, textTransform: 'uppercase', color: E.inkMute }}>Listened, year-to-date</div>
          <SerifNum value="187" unit="hrs" size={88}/>
          <div style={{ fontFamily: E.serif, fontSize: 17, fontStyle: 'italic', color: E.inkSoft, marginTop: 4 }}>and twenty-four minutes.</div>
        </div>
        <div style={{ flexShrink: 0, paddingBottom: 10, textAlign: 'right' }}>
          <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 2, color: E.inkMute }}>+34% YoY</div>
          {/* mini sparkline */}
          <svg width="84" height="38" style={{ marginTop: 6 }}>
            {(() => {
              const pts = STATS.weekly;
              const max = Math.max(...pts);
              const path = pts.map((v, i) => `${i ? 'L' : 'M'}${(i / (pts.length - 1)) * 80 + 2},${34 - (v / max) * 28}`).join(' ');
              return <g>
                <path d={path + ' L82,34 L2,34 Z'} fill={E.accentTint}/>
                <path d={path} stroke={E.accent} strokeWidth="1.4" fill="none"/>
              </g>;
            })()}
          </svg>
        </div>
      </div>

      <Rule thick m={16}/>

      {/* Three feature numbers */}
      <div style={{ padding: '12px 24px 0', display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 0 }}>
        {[
          { v: '31', u: 'h', l: 'Trimmed' },
          { v: '287', u: '/412', l: 'Finished' },
          { v: '47', u: 'd', l: 'Streak' },
        ].map((s, i) => (
          <div key={i} style={{
            paddingLeft: i ? 14 : 0, paddingRight: i < 2 ? 14 : 0,
            borderRight: i < 2 ? `1px solid ${E.faint}` : 'none',
          }}>
            <SerifNum value={s.v} unit={s.u} size={36}/>
            <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 1.6, color: E.inkMute, textTransform: 'uppercase', marginTop: 6 }}>{s.l}</div>
          </div>
        ))}
      </div>

      <SectionLabel n="01" right="Last 26 weeks">When you listened</SectionLabel>

      {/* Heatmap */}
      <div style={{ padding: '0 24px' }}>
        <div style={{ display: 'flex', gap: 6, alignItems: 'flex-start' }}>
          <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', height: 84, fontFamily: E.mono, fontSize: 8, color: E.inkMute, letterSpacing: 1, paddingTop: 1 }}>
            <span>S</span><span>W</span><span>S</span>
          </div>
          <div style={{ display: 'flex', gap: 3, flex: 1 }}>
            {STATS.heatmap.map((col, i) => (
              <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 3, flex: 1 }}>
                {col.map((v, j) => (
                  <div key={j} style={{
                    aspectRatio: '1', borderRadius: 2,
                    background: ['transparent', E.accentTint, E.accentSoft, E.accent, '#7d2917'][v],
                    border: v === 0 ? `0.5px solid ${E.faint}` : 'none',
                  }}/>
                ))}
              </div>
            ))}
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontFamily: E.mono, fontSize: 9, color: E.inkMute, letterSpacing: 1.4, textTransform: 'uppercase' }}>
          <span>Nov ’25</span><span>Jan</span><span>Mar</span><span>May ’26</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 10, fontFamily: E.mono, fontSize: 9, color: E.inkMute, letterSpacing: 1.2 }}>
          <span>LESS</span>
          {[E.accentTint, E.accentSoft, E.accent, '#7d2917'].map((c, i) => <div key={i} style={{ width: 9, height: 9, background: c, borderRadius: 2 }}/>)}
          <span>MORE</span>
          <span style={{ marginLeft: 'auto', color: E.inkSoft, fontFamily: E.serif, fontStyle: 'italic', fontSize: 13, letterSpacing: 0, textTransform: 'none' }}>Tuesdays loudest.</span>
        </div>
      </div>

      <SectionLabel n="02" right="Page 02 — Page 18">In this issue</SectionLabel>

      {/* Index */}
      <div style={{ padding: '0 24px' }}>
        {[
          { n: '01', t: 'Subscriptions',  s: 'Where the hours went, ranked.', p: '02', acc: '38h' },
          { n: '02', t: 'Activity',       s: 'Started, finished, abandoned.', p: '08', acc: '70%' },
          { n: '03', t: 'Six years',      s: 'A reading list, by year.',      p: '12', acc: '729h' },
          { n: '04', t: 'Time saved',     s: 'Speed, silence, intros.',       p: '18', acc: '31h' },
        ].map((row, i, arr) => (
          <div key={i} style={{
            display: 'flex', alignItems: 'baseline', padding: '14px 0', gap: 12,
            borderBottom: i < arr.length - 1 ? `1px solid ${E.faint}` : 'none',
          }}>
            <span style={{ fontFamily: E.mono, fontSize: 10, color: E.inkMute, width: 26, letterSpacing: 1 }}>{row.n}</span>
            <div style={{ flex: 1 }}>
              <div style={{ fontFamily: E.serif, fontSize: 22, color: E.ink, lineHeight: 1.05, letterSpacing: -0.3 }}>{row.t}</div>
              <div style={{ fontSize: 12, color: E.inkMute, marginTop: 2 }}>{row.s}</div>
            </div>
            <span style={{ fontFamily: E.serif, fontSize: 16, color: E.accent, fontStyle: 'italic' }}>{row.acc}</span>
            <span style={{ fontFamily: E.mono, fontSize: 10, color: E.inkMute, letterSpacing: 1.2, width: 32, textAlign: 'right' }}>p.{row.p}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// 2 · Subscriptions — donut + per-show list (Calm structure, editorial paint)
// ─────────────────────────────────────────────────────────

function Donut({ data, size = 200, stroke = 28 }) {
  const cx = size / 2, cy = size / 2, r = (size - stroke) / 2 - 1;
  const C = 2 * Math.PI * r;
  let acc = 0;
  return (
    <svg width={size} height={size} style={{ display: 'block' }}>
      {/* Track */}
      <circle cx={cx} cy={cy} r={r} stroke={E.faint} strokeWidth={stroke} fill="none"/>
      {/* Segments */}
      {data.map((d, i) => {
        const len = (d.pct / 100) * C;
        const off = -acc - 0.6;
        acc += len;
        return (
          <circle
            key={i} cx={cx} cy={cy} r={r}
            stroke={d.color} strokeWidth={stroke} fill="none"
            strokeDasharray={`${Math.max(0, len - 1.2)} ${C - Math.max(0, len - 1.2)}`}
            strokeDashoffset={off}
            transform={`rotate(-90 ${cx} ${cy})`}
            strokeLinecap="butt"
          />
        );
      })}
      {/* Inner edge stroke */}
      <circle cx={cx} cy={cy} r={r - stroke / 2} fill="none" stroke={E.faint} strokeWidth="0.5"/>
      <circle cx={cx} cy={cy} r={r + stroke / 2} fill="none" stroke={E.faint} strokeWidth="0.5"/>
      {/* Center stat */}
      <text x={cx} y={cy - 14} textAnchor="middle" fontFamily={E.mono} fontSize="9" letterSpacing="1.6" fill={E.inkMute} style={{ textTransform: 'uppercase' }}>This year</text>
      <text x={cx} y={cy + 14} textAnchor="middle" fontFamily={E.serif} fontSize="42" fill={E.ink} letterSpacing="-1.2">187<tspan fontStyle="italic" fill={E.accent} fontSize="20"> h</tspan></text>
      <text x={cx} y={cy + 32} textAnchor="middle" fontFamily={E.mono} fontSize="9" letterSpacing="1.4" fill={E.inkMute} style={{ textTransform: 'uppercase' }}>9 shows · 24m</text>
    </svg>
  );
}

function Subscriptions() {
  return (
    <div style={{ background: E.bg, fontFamily: E.sans, color: E.ink, paddingBottom: 36, minHeight: '100%' }}>
      <Mast
        vol="Vol. 06" page="Ch. 01"
        kicker="Chapter One"
        title={<>Where the<br/><i style={{ fontStyle: 'italic', color: E.accent }}>hours</i> went.</>}
        dateline="Jan 1 – May 10 · 187 hours across 38 shows"
      />
      <Tabs items={['Shows', 'Activity', 'Years', 'Saved']} active={0}/>

      {/* Donut + ranked top three */}
      <div style={{ padding: '20px 24px 8px', display: 'flex', alignItems: 'center', gap: 10 }}>
        <Donut data={STATS.shows.slice(0, 8)}/>
        <div style={{ flex: 1, minWidth: 0 }}>
          {STATS.shows.slice(0, 3).map((s, i) => (
            <div key={s.id} style={{ paddingBottom: 8, marginBottom: 8, borderBottom: i < 2 ? `1px dashed ${E.faint}` : 'none' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 1.2, color: E.inkMute }}>{String(i + 1).padStart(2, '0')}</span>
                <div style={{ width: 6, height: 6, borderRadius: '50%', background: s.color }}/>
              </div>
              <div style={{ fontFamily: E.serif, fontSize: 15, color: E.ink, lineHeight: 1.1, marginTop: 2, letterSpacing: -0.2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.title}</div>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginTop: 2 }}>
                <span style={{ fontFamily: E.serif, fontSize: 16, color: E.ink, letterSpacing: -0.2 }}>{s.hrs.toFixed(1)}<span style={{ fontStyle: 'italic', color: E.accent, fontSize: 11 }}>h</span></span>
                <span style={{ fontFamily: E.mono, fontSize: 9, color: E.inkMute, letterSpacing: 1 }}>{s.pct}%</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Filter chips, library catalog feel */}
      <div style={{ padding: '4px 24px 0', display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        {[
          { l: 'All shows', active: true },
          { l: 'This year' },
          { l: 'Comedy' },
          { l: 'News' },
          { l: '+ filter' },
        ].map((c, i) => (
          <span key={i} style={{
            padding: '4px 10px', fontFamily: E.mono, fontSize: 10, letterSpacing: 1.2, textTransform: 'uppercase',
            border: `1px solid ${c.active ? E.ink : E.faint}`, borderRadius: 100,
            background: c.active ? E.ink : 'transparent', color: c.active ? E.bg : E.inkSoft,
          }}>{c.l}</span>
        ))}
      </div>

      <SectionLabel n="01" right="Sorted by hours">All subscriptions · 9</SectionLabel>

      {/* Per-show list — Calm’s data viz, editorial palette */}
      <div style={{ padding: '0 24px' }}>
        {STATS.shows.map((s, i, arr) => {
          const last = i === arr.length - 1;
          return (
            <div key={s.id} style={{
              display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0',
              borderBottom: last ? 'none' : `1px solid ${E.faint}`,
            }}>
              <span style={{ fontFamily: E.mono, fontSize: 10, color: E.inkMute, width: 18, letterSpacing: 1 }}>{String(i + 1).padStart(2, '0')}</span>
              <ECover color={s.color} title={s.title} size={40}/>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontFamily: E.serif, fontSize: 17, color: E.ink, letterSpacing: -0.2, lineHeight: 1.1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.title}</div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
                  <div style={{ flex: 1, height: 3, background: E.faint, overflow: 'hidden' }}>
                    <div style={{ width: `${s.pct * 4.5}%`, maxWidth: '100%', height: '100%', background: s.color }}/>
                  </div>
                  <span style={{ fontFamily: E.mono, fontSize: 9, color: E.inkMute, letterSpacing: 1 }}>{s.pct}%</span>
                </div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontFamily: E.serif, fontSize: 20, color: E.ink, letterSpacing: -0.3, lineHeight: 1 }}>{s.hrs.toFixed(1)}<span style={{ fontStyle: 'italic', color: E.accent, fontSize: 12 }}>h</span></div>
                <div style={{ fontFamily: E.mono, fontSize: 8, color: E.inkMute, marginTop: 2, letterSpacing: 1 }}>{Math.round(s.hrs * 60 / 52)}m / wk</div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Footnote */}
      <div style={{ padding: '20px 24px 0', fontFamily: E.serif, fontSize: 13, fontStyle: 'italic', color: E.inkMute, lineHeight: 1.5 }}>
        <span style={{ color: E.accent, fontStyle: 'normal', marginRight: 6 }}>※</span>
        “Other” gathers 23 shows that account for less than 4% of listening each.
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// 3 · Activity — habits + finished/abandoned + saved
// ─────────────────────────────────────────────────────────

function Activity() {
  const max = Math.max(...STATS.byHour);
  const peakHour = STATS.byHour.indexOf(max);

  const days = ['S','M','T','W','T','F','S'];
  const dayMax = Math.max(...STATS.byDay);

  return (
    <div style={{ background: E.bg, fontFamily: E.sans, color: E.ink, paddingBottom: 36, minHeight: '100%' }}>
      <Mast
        vol="Vol. 06" page="Ch. 02"
        kicker="Chapter Two"
        title={<>Habits &amp;<br/><i style={{ fontStyle: 'italic', color: E.accent }}>silences.</i></>}
        dateline="Episodes started, finished, walked away from"
      />
      <Tabs items={['Shows', 'Activity', 'Years', 'Saved']} active={1}/>

      {/* Four-cell hangs */}
      <div style={{ padding: '20px 24px 0', display: 'grid', gridTemplateColumns: '1fr 1fr', columnGap: 18, rowGap: 20 }}>
        {[
          { v: '412', l: 'Started',     hint: '+58 vs ’25' },
          { v: '287', l: 'Finished',    hint: '70% completion' },
          { v: '125', l: 'In progress', hint: 'avg 18m left' },
          { v: '38',  l: 'Abandoned',   hint: '9% walked away' },
        ].map((r, i) => (
          <div key={i} style={{ borderTop: `1px solid ${E.rule}`, paddingTop: 8 }}>
            <SerifNum value={r.v} size={42}/>
            <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 1.6, textTransform: 'uppercase', color: E.ink, marginTop: 8, fontWeight: 600 }}>{r.l}</div>
            <div style={{ fontFamily: E.serif, fontStyle: 'italic', fontSize: 13, color: E.inkMute, marginTop: 2 }}>{r.hint}</div>
          </div>
        ))}
      </div>

      <SectionLabel n="01" right="Local time, weekday avg">When you listen</SectionLabel>

      {/* Hour-of-day bars */}
      <div style={{ padding: '0 24px' }}>
        <p style={{ fontFamily: E.serif, fontSize: 19, color: E.ink, lineHeight: 1.25, margin: 0, letterSpacing: -0.3 }}>
          You start your day on commute and peak <span style={{ color: E.accent, fontStyle: 'italic' }}>at 8 a.m.</span>; a quiet stretch follows lunch, then a smaller spike around six.
        </p>
        <div style={{ position: 'relative', marginTop: 18 }}>
          <svg viewBox="0 0 360 110" style={{ width: '100%', height: 130, display: 'block' }}>
            {/* Baseline */}
            <line x1="0" y1="92" x2="360" y2="92" stroke={E.ink} strokeWidth="0.6" opacity="0.2"/>
            {/* gridline at half */}
            <line x1="0" y1="56" x2="360" y2="56" stroke={E.ink} strokeWidth="0.5" opacity="0.08" strokeDasharray="2 2"/>
            {STATS.byHour.map((v, i) => {
              const w = 360 / 24;
              const h = (v / max) * 80;
              const isPeak = i === peakHour;
              return <rect key={i} x={i * w + 1.5} y={92 - h} width={w - 3} height={h} fill={isPeak ? E.accent : E.ink} opacity={isPeak ? 1 : 0.78}/>;
            })}
            {/* Peak marker */}
            <line x1={(peakHour + 0.5) * 360 / 24} y1={92 - (max / max) * 80 - 6} x2={(peakHour + 0.5) * 360 / 24} y2={92 - (max / max) * 80 - 14} stroke={E.accent} strokeWidth="0.8"/>
            <text x={(peakHour + 0.5) * 360 / 24} y={92 - (max / max) * 80 - 18} textAnchor="middle" fontFamily={E.mono} fontSize="9" letterSpacing="1.4" fill={E.accent} style={{ textTransform: 'uppercase' }}>Peak · 38m</text>
          </svg>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontFamily: E.mono, fontSize: 9, color: E.inkMute, letterSpacing: 1, marginTop: 2 }}>
            <span>00</span><span>06</span><span>12</span><span>18</span><span>23</span>
          </div>
        </div>
      </div>

      <SectionLabel n="02">Day of the week</SectionLabel>

      {/* Per-day small multiples */}
      <div style={{ padding: '0 24px' }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 6 }}>
          {STATS.byDay.map((v, i) => {
            const isPeak = v === dayMax;
            return (
              <div key={i} style={{ textAlign: 'center' }}>
                <div style={{ height: 70, display: 'flex', alignItems: 'flex-end', justifyContent: 'center', position: 'relative' }}>
                  <div style={{ width: '100%', height: `${(v / dayMax) * 100}%`, background: isPeak ? E.accent : E.ink, opacity: isPeak ? 1 : 0.78 }}/>
                </div>
                <div style={{ fontFamily: E.mono, fontSize: 10, color: isPeak ? E.accent : E.inkMute, marginTop: 6, letterSpacing: 1, fontWeight: isPeak ? 600 : 400 }}>{days[i]}</div>
                <div style={{ fontFamily: E.serif, fontSize: 13, color: E.ink, marginTop: 2, letterSpacing: -0.2 }}>{v}<span style={{ fontStyle: 'italic', color: isPeak ? E.accent : E.inkMute, fontSize: 10 }}>m</span></div>
              </div>
            );
          })}
        </div>
      </div>

      <Rule thick m={20}/>

      {/* Time saved */}
      <div style={{ padding: '8px 24px 0' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
          <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 1.6, textTransform: 'uppercase', color: E.inkMute }}>Time saved</div>
          <span style={{ flex: 1, height: 1, background: E.faint, alignSelf: 'center' }}/>
          <span style={{ fontFamily: E.mono, fontSize: 9, color: E.inkMute, letterSpacing: 1.2 }}>17% OF AUDIO</span>
        </div>
        <div style={{ marginTop: 10 }}>
          <SerifNum value="31" unit="hrs 12 min" size={56} italic={true}/>
        </div>

        <div style={{ marginTop: 14 }}>
          {[
            { l: 'Faster playback', sub: 'Avg 1.6× speed', v: '22h 24m', pct: 72, c: E.accent },
            { l: 'Skip silence',    sub: '7,432 gaps',    v: '6h 06m',  pct: 19, c: E.gold },
            { l: 'Skip intros',     sub: '142 episodes',  v: '2h 42m',  pct:  9, c: E.inkSoft },
          ].map((r, i, arr) => (
            <div key={i} style={{ display: 'flex', alignItems: 'baseline', padding: '12px 0', borderBottom: i < arr.length - 1 ? `1px dashed ${E.faint}` : 'none', gap: 10 }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontFamily: E.serif, fontSize: 19, color: E.ink, lineHeight: 1.1, letterSpacing: -0.2 }}>{r.l}</div>
                <div style={{ fontSize: 11, color: E.inkMute, marginTop: 2, fontStyle: 'italic', fontFamily: E.serif }}>{r.sub}</div>
              </div>
              <div style={{ width: 88 }}>
                <div style={{ height: 4, background: E.faint, overflow: 'hidden' }}>
                  <div style={{ width: `${r.pct}%`, height: '100%', background: r.c }}/>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4, fontFamily: E.mono, fontSize: 9, color: E.inkMute, letterSpacing: 1 }}>
                  <span>{r.pct}%</span><span style={{ color: E.ink }}>{r.v}</span>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// 4 · Years — long-form streamgraph
// ─────────────────────────────────────────────────────────

function Years() {
  const data = STATS.yearly;
  const total = data.reduce((s, d) => s + d.hrs, 0);
  const max = Math.max(...data.map(d => d.hrs));

  return (
    <div style={{ background: E.bg, fontFamily: E.sans, color: E.ink, paddingBottom: 36, minHeight: '100%' }}>
      <Mast
        vol="Vol. 06" page="Ch. 03"
        kicker="Chapter Three"
        title={<>Six<br/><i style={{ fontStyle: 'italic', color: E.accent }}>years.</i></>}
        dateline="A reading list, by year · 2021 — 2026"
      />
      <Tabs items={['Shows', 'Activity', 'Years', 'Saved']} active={2}/>

      <div style={{ padding: '20px 24px 0' }}>
        <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 1.8, textTransform: 'uppercase', color: E.inkMute }}>All time, since 2021</div>
        <SerifNum value={total} unit="hrs" size={84}/>
        <div style={{ fontFamily: E.serif, fontStyle: 'italic', fontSize: 16, color: E.inkSoft, marginTop: 4 }}>
          That’s 30 days, 9 hours of audio. <span style={{ color: E.accent, fontStyle: 'normal', fontFamily: E.sans, fontSize: 12, fontWeight: 500 }}>+34% on 2024</span>
        </div>
      </div>

      {/* Streamgraph */}
      <div style={{ padding: '24px 8px 8px' }}>
        <svg viewBox="0 0 380 200" style={{ width: '100%', height: 220, display: 'block' }}>
          {(() => {
            const w = 380, h = 180, n = data.length;
            const pad = 28;
            const stepX = (w - pad * 2) / (n - 1);
            const baseY = h - 18;
            const top = data.map((d, i) => [pad + i * stepX, baseY - (d.hrs / max) * (h - 60)]);

            // Smooth curve
            const smooth = (pts) => {
              if (pts.length < 2) return '';
              let p = `M${pts[0][0]},${pts[0][1]}`;
              for (let i = 1; i < pts.length; i++) {
                const [x0, y0] = pts[i - 1], [x1, y1] = pts[i];
                const cx = (x0 + x1) / 2;
                p += ` C${cx},${y0} ${cx},${y1} ${x1},${y1}`;
              }
              return p;
            };

            return (
              <g>
                <defs>
                  <linearGradient id="ystream" x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0" stopColor={E.accent} stopOpacity="0.34"/>
                    <stop offset="1" stopColor={E.accent} stopOpacity="0.02"/>
                  </linearGradient>
                </defs>
                {/* baseline + ticks */}
                <line x1={pad} y1={baseY} x2={w - pad} y2={baseY} stroke={E.ink} strokeWidth="0.6" opacity="0.25"/>
                {[0.25, 0.5, 0.75].map(f => (
                  <line key={f} x1={pad} y1={baseY - f * (h - 60)} x2={w - pad} y2={baseY - f * (h - 60)} stroke={E.ink} strokeWidth="0.4" opacity="0.08" strokeDasharray="2 3"/>
                ))}
                <path d={`${smooth(top)} L${w - pad},${baseY} L${pad},${baseY} Z`} fill="url(#ystream)"/>
                <path d={smooth(top)} stroke={E.accent} strokeWidth="1.6" fill="none"/>
                {top.map((p, i) => {
                  const isMax = data[i].hrs === max;
                  const isCur = i === data.length - 1;
                  return (
                    <g key={i}>
                      <circle cx={p[0]} cy={p[1]} r={isMax ? 4 : 2.6} fill={E.bg} stroke={E.accent} strokeWidth="1.4"/>
                      <text x={p[0]} y={p[1] - 12} textAnchor="middle" fontFamily={E.serif} fontSize="15" fill={E.ink} letterSpacing="-0.2">{data[i].hrs}</text>
                      <text x={p[0]} y={baseY + 14} textAnchor="middle" fontFamily={E.mono} fontSize="9" fill={isMax ? E.accent : E.inkMute} letterSpacing="1.2">'{String(data[i].year).slice(2)}</text>
                      {isMax && <text x={p[0]} y={p[1] - 26} textAnchor="middle" fontFamily={E.mono} fontSize="8" letterSpacing="1.4" fill={E.accent} style={{ textTransform: 'uppercase' }}>Peak</text>}
                      {isCur && <text x={p[0]} y={p[1] - 26} textAnchor="middle" fontFamily={E.mono} fontSize="8" letterSpacing="1.4" fill={E.inkMute} style={{ textTransform: 'uppercase' }}>YTD</text>}
                    </g>
                  );
                })}
              </g>
            );
          })()}
        </svg>
      </div>

      <SectionLabel n="01" right="Year over year">By year</SectionLabel>

      {/* Per-year list with deltas */}
      <div style={{ padding: '0 24px' }}>
        {data.slice().reverse().map((y, i, arr) => {
          const prev = arr[i + 1];
          const delta = prev ? Math.round(((y.hrs - prev.hrs) / prev.hrs) * 100) : null;
          const deltaPos = delta !== null && delta >= 0;
          return (
            <div key={y.year} style={{
              display: 'grid', gridTemplateColumns: '60px 1fr 96px', alignItems: 'baseline',
              padding: '14px 0', gap: 12,
              borderBottom: i < arr.length - 1 ? `1px solid ${E.faint}` : 'none',
            }}>
              <div style={{ fontFamily: E.serif, fontSize: 28, color: E.ink, lineHeight: 1, letterSpacing: -0.4 }}>{y.year}</div>
              <div>
                <div style={{ height: 5, background: E.faint, overflow: 'hidden' }}>
                  <div style={{ width: `${(y.hrs / max) * 100}%`, height: '100%', background: y.hrs === max ? E.accent : E.ink, opacity: y.hrs === max ? 1 : 0.85 }}/>
                </div>
                <div style={{ fontFamily: E.mono, fontSize: 9, color: E.inkMute, marginTop: 5, letterSpacing: 1, textTransform: 'uppercase' }}>
                  {Math.round(y.hrs / 12)} hrs/mo · avg
                </div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontFamily: E.serif, fontSize: 22, color: E.ink, lineHeight: 1, letterSpacing: -0.3 }}>{y.hrs}<span style={{ fontStyle: 'italic', color: E.accent, fontSize: 12 }}>h</span></div>
                {delta !== null && (
                  <div style={{ fontFamily: E.mono, fontSize: 9, color: deltaPos ? '#3a7a3a' : E.accent, marginTop: 3, letterSpacing: 1 }}>
                    {deltaPos ? '▲' : '▼'} {Math.abs(delta)}%
                  </div>
                )}
                {delta === null && <div style={{ fontFamily: E.mono, fontSize: 9, color: E.inkMute, marginTop: 3, letterSpacing: 1 }}>— FIRST YEAR</div>}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// 5 · Per-feed dialog — dossier card
// ─────────────────────────────────────────────────────────

function FeedDialog() {
  const max = Math.max(...FEED.weekly);
  return (
    <div style={{ position: 'relative', height: '100%', background: E.bg, fontFamily: E.sans, color: E.ink, overflow: 'hidden' }}>
      {/* Backdrop: blurred Subscriptions page */}
      <div style={{ position: 'absolute', inset: 0, opacity: 0.45, filter: 'blur(2px) saturate(0.9)' }}>
        <Mast vol="Vol. 06" page="Ch. 01" kicker="Chapter One" title={<>Where the<br/>hours went.</>}/>
      </div>
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(21,17,13,0.42)' }}/>

      {/* Dossier */}
      <div style={{
        position: 'absolute', left: 14, right: 14, top: '50%', transform: 'translateY(-50%)',
        background: E.paper, border: `1px solid ${E.ink}`,
        boxShadow: '8px 8px 0 0 rgba(21,17,13,0.85)',
        overflow: 'hidden',
      }}>
        {/* Header strip */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '10px 14px', borderBottom: `1px solid ${E.ink}`,
          fontFamily: E.mono, fontSize: 9, letterSpacing: 1.8, textTransform: 'uppercase', color: E.inkMute,
        }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ width: 6, height: 6, background: E.accent, borderRadius: '50%' }}/>
            Dossier · No. 02
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>FILED MAY 10 ✕</span>
        </div>

        {/* Title block */}
        <div style={{ padding: '20px 20px 0', display: 'flex', gap: 14 }}>
          <ECover color={FEED.color} title={FEED.title} size={72}/>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 1.6, textTransform: 'uppercase', color: E.accent }}>Subscribed since {FEED.subscribed}</div>
            <h2 style={{ fontFamily: E.serif, fontSize: 30, fontWeight: 400, color: E.ink, lineHeight: 1, letterSpacing: -0.6, margin: '6px 0 0' }}>{FEED.title}</h2>
            <div style={{ fontFamily: E.serif, fontStyle: 'italic', fontSize: 14, color: E.inkMute, marginTop: 4 }}>by {FEED.host}</div>
          </div>
        </div>

        {/* Three big numbers */}
        <div style={{
          display: 'grid', gridTemplateColumns: '1fr 1fr 1fr',
          marginTop: 18, borderTop: `1px solid ${E.ink}`, borderBottom: `1px solid ${E.ink}`,
        }}>
          {[
            { v: '27.1', u: 'h listened',    sub: '#02 of 38' },
            { v: '4.8',  u: 'h saved',       sub: '15% trim' },
            { v: '86',   u: 'of 142 played', sub: '60% caught' },
          ].map((m, i) => (
            <div key={i} style={{
              padding: '14px 12px', textAlign: 'center',
              borderRight: i < 2 ? `1px solid ${E.ink}` : 'none',
            }}>
              <SerifNum value={m.v} size={28}/>
              <div style={{ fontFamily: E.mono, fontSize: 9, color: E.inkMute, letterSpacing: 1.4, textTransform: 'uppercase', marginTop: 6 }}>{m.u}</div>
              <div style={{ fontFamily: E.serif, fontStyle: 'italic', fontSize: 11, color: E.accent, marginTop: 3 }}>{m.sub}</div>
            </div>
          ))}
        </div>

        {/* Sparkline + facts */}
        <div style={{ padding: '14px 20px 6px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <div style={{ fontFamily: E.mono, fontSize: 9, letterSpacing: 1.6, textTransform: 'uppercase', color: E.inkMute }}>Last 12 weeks · hrs</div>
            <div style={{ fontFamily: E.serif, fontStyle: 'italic', fontSize: 12, color: E.accent }}>↗ trending up</div>
          </div>
          <svg viewBox="0 0 320 60" style={{ width: '100%', height: 60, marginTop: 6 }}>
            {(() => {
              const pts = FEED.weekly.map((v, i) => [(i / (FEED.weekly.length - 1)) * 320, 50 - (v / max) * 40]);
              const path = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0]},${p[1]}`).join(' ');
              return (
                <g>
                  <path d={`${path} L320,55 L0,55 Z`} fill={E.accentTint}/>
                  <path d={path} stroke={E.accent} strokeWidth="1.6" fill="none"/>
                  {pts.map((p, i) => <circle key={i} cx={p[0]} cy={p[1]} r="2" fill={E.paper} stroke={E.accent} strokeWidth="1"/>)}
                </g>
              );
            })()}
          </svg>
        </div>

        {/* Tiny attributes */}
        <div style={{ padding: '4px 20px 14px', display: 'flex', justifyContent: 'space-between', fontFamily: E.mono, fontSize: 10, color: E.inkMute, letterSpacing: 1.2 }}>
          <span>AVG · 52 MIN</span>
          <span>RSS · TWICE WEEKLY</span>
          <span>NEXT · TUE</span>
        </div>

        {/* Action strip */}
        <div style={{ display: 'flex', borderTop: `1px solid ${E.ink}` }}>
          <button style={{
            flex: 1, padding: '14px 12px', border: 'none', background: 'transparent',
            fontFamily: E.serif, fontSize: 16, color: E.ink, letterSpacing: -0.2, cursor: 'pointer',
            borderRight: `1px solid ${E.ink}`,
          }}>Open feed →</button>
          <button style={{
            flex: 1, padding: '14px 12px', border: 'none', background: E.ink,
            fontFamily: E.serif, fontSize: 16, color: E.bg, letterSpacing: -0.2, cursor: 'pointer',
          }}>▸ Listen now</button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { Overview, Subscriptions, Activity, Years, FeedDialog });
