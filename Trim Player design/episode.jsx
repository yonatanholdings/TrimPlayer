// episode.jsx — Episode screen: header, player, segment timeline, segment list.

const EPISODE = {
  show: 'The Decoder Hour',
  title: 'Why your interface feels slow — and the 100 ms rule',
  meta: 'EP 142 · May 28 · 52 min',
  art: '#2a9d8f', art2: '#1f6e60',
  dur: 3122,
  segments: [
    { id: 'g1', type: 'intro',   start: 0,    end: 38,   conf: 0.93, votes: 214 },
    { id: 'g2', type: 'ad',      start: 318,  end: 408,  conf: 0.72, votes: 86  },
    { id: 'g3', type: 'sponsor', start: 1240, end: 1296, conf: 0.58, votes: 39  },
    { id: 'g4', type: 'silence', start: 1884, end: 1903, conf: 0.96, votes: 11  },
    { id: 'g5', type: 'ad',      start: 2506, end: 2598, conf: 0.51, votes: 28  },
  ],
};
const WAVE = waveAmps(88, 13);

function confColor(conf, t) {
  if (conf >= 0.8) return t.good;
  if (conf >= 0.62) return t.primary;
  return t.warn;
}

// ── Timeline with segment regions ──────────────────────────────────────────
function Timeline({ theme, ep, playhead, onSeek, selectedId, onOpen, suppress }) {
  const t = theme;
  const railRef = useRef(null);
  const segAt = (frac) => frac * ep.dur;

  const onPointer = (e) => {
    const r = railRef.current.getBoundingClientRect();
    const frac = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    onSeek(segAt(frac));
    const move = (ev) => {
      const f = Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width));
      onSeek(segAt(f));
    };
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };

  const phFrac = playhead / ep.dur;
  const segOf = (frac) => ep.segments.find(s => frac * ep.dur >= s.start && frac * ep.dur < s.end);

  return (
    <div style={{ padding: '0 20px' }}>
      {/* segment region rail (tap targets) */}
      <div style={{ position: 'relative', height: 26, marginBottom: 2 }}>
        {ep.segments.map(s => {
          const left = (s.start / ep.dur) * 100, w = ((s.end - s.start) / ep.dur) * 100;
          const c = t.seg[s.type];
          const sel = selectedId === s.id;
          return (
            <button key={s.id} onClick={() => onOpen(s)} style={{
              position: 'absolute', left: `${left}%`, width: `max(${w}%, 10px)`, top: 0, height: 26,
              padding: 0, border: 'none', background: 'transparent', cursor: 'pointer',
              display: 'flex', alignItems: 'flex-end',
            }}>
              <div style={{
                width: '100%', height: sel ? 12 : 7, borderRadius: 4, background: c.solid,
                boxShadow: sel ? `0 0 0 2px ${t.surface}, 0 0 0 4px ${c.solid}` : 'none',
                opacity: suppress === s.id ? 0.45 : 1, transition: 'height .15s',
              }}/>
            </button>
          );
        })}
      </div>

      {/* waveform + playhead */}
      <div ref={railRef} onPointerDown={onPointer} style={{ position: 'relative', height: 52, display: 'flex', alignItems: 'center', gap: 2, cursor: 'pointer', touchAction: 'none' }}>
        {WAVE.map((a, i) => {
          const frac = (i + 0.5) / WAVE.length;
          const seg = segOf(frac);
          const played = frac <= phFrac;
          let col = played ? t.primary : t.outlineVar;
          if (seg) col = t.seg[seg.type].solid;
          const op = seg ? (played ? 1 : 0.5) : (played ? 1 : 0.55);
          return <div key={i} style={{ flex: 1, height: `${a * 100}%`, minHeight: 3, borderRadius: 2, background: col, opacity: op }}/>;
        })}
        {/* playhead */}
        <div style={{ position: 'absolute', left: `${phFrac * 100}%`, top: -3, bottom: -3, width: 2.5, background: t.onSurf, borderRadius: 2, transform: 'translateX(-50%)', pointerEvents: 'none' }}>
          <div style={{ position: 'absolute', top: -5, left: '50%', transform: 'translateX(-50%)', width: 13, height: 13, borderRadius: 7, background: t.onSurf, border: `2.5px solid ${t.surface}` }}/>
        </div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 7, fontFamily: t.font, fontSize: 12, color: t.onSurfMute, fontVariantNumeric: 'tabular-nums', letterSpacing: 0.2 }}>
        <span>{fmt(playhead)}</span>
        <span>−{fmt(ep.dur - playhead)}</span>
      </div>
    </div>
  );
}

// ── Selected-segment contextual action bar ─────────────────────────────────
function SegContext({ theme, seg, onReplay, onReport, onClose }) {
  const t = theme; const m = SEG_META[seg.type]; const c = t.seg[seg.type];
  return (
    <div style={{
      margin: '12px 20px 0', padding: '10px 10px 10px 14px', borderRadius: 18,
      background: t.scHigh, display: 'flex', alignItems: 'center', gap: 10,
      fontFamily: t.font, animation: 'mu-pop .18s ease',
    }}>
      <Chip icon={m.icon} theme={t} c={c} small>{m.label}</Chip>
      <span style={{ fontSize: 13, color: t.onSurfVar, fontVariantNumeric: 'tabular-nums' }}>{fmt(seg.start)}–{fmt(seg.end)}</span>
      <div style={{ flex: 1 }}/>
      <IconBtn name="replay" theme={t} btn={40} size={20} onClick={onReplay} bg={t.surface}/>
      <button onClick={onReport} style={{
        display: 'inline-flex', alignItems: 'center', gap: 6, height: 40, padding: '0 16px', borderRadius: 20,
        background: t.primary, color: t.onPrimary, border: 'none', cursor: 'pointer', fontFamily: t.font, fontWeight: 600, fontSize: 13.5,
      }}><MIcon name="flag" size={17} color={t.onPrimary}/> Report</button>
    </div>
  );
}

// ── Segment list row ────────────────────────────────────────────────────────
function SegRow({ theme, seg, onReport, onConfirm, onReplay, confirmed, compact, voting }) {
  const t = theme; const m = SEG_META[seg.type]; const c = t.seg[seg.type];
  const cc = confColor(seg.conf, t);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: compact ? '9px 20px' : '13px 20px' }}>
      <div style={{ width: 42, height: 42, borderRadius: 13, background: c.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
        <MIcon name={m.icon} size={21} color={c.fg} stroke={2.1}/>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <span style={{ fontFamily: t.font, fontSize: 15.5, fontWeight: 600, color: t.onSurf }}>{m.label}</span>
          <span style={{ fontFamily: t.font, fontSize: 12.5, color: t.onSurfMute, fontVariantNumeric: 'tabular-nums' }}>{fmt(seg.start)}–{fmt(seg.end)}</span>
          <span style={{ fontFamily: t.font, fontSize: 12.5, color: t.onSurfMute }}>· {fmtDur(seg.end - seg.start)}</span>
        </div>
        {voting && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 7 }}>
            <div style={{ flex: 1, maxWidth: 130, height: 4, borderRadius: 2, background: t.surfVar, overflow: 'hidden' }}>
              <div style={{ width: `${Math.round(seg.conf * 100)}%`, height: '100%', background: cc, borderRadius: 2 }}/>
            </div>
            <span style={{ fontFamily: t.font, fontSize: 11.5, color: cc, fontWeight: 600 }}>{Math.round(seg.conf * 100)}%</span>
            <span style={{ fontFamily: t.font, fontSize: 11.5, color: t.onSurfMute }}>· {seg.votes} votes</span>
          </div>
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 2, flexShrink: 0 }}>
        {confirmed
          ? <div style={{ width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><MIcon name="check" size={20} color={t.good}/></div>
          : <IconBtn name="thumb" theme={t} btn={44} size={20} onClick={onConfirm} color={t.onSurfMute}/>}
        <IconBtn name="flag" theme={t} btn={44} size={20} onClick={onReport} color={t.onSurfVar}/>
      </div>
    </div>
  );
}

// ── Full episode screen ─────────────────────────────────────────────────────
function EpisodeScreen({ theme, ep, ctx }) {
  const t = theme;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: t.surface, fontFamily: t.font }}>
      {/* App bar */}
      <div style={{ display: 'flex', alignItems: 'center', padding: '6px 6px', flexShrink: 0 }}>
        <IconBtn name="back" theme={t} color={t.onSurf}/>
        <div style={{ flex: 1, textAlign: 'center', fontSize: 14, fontWeight: 600, color: t.onSurfVar, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', padding: '0 4px' }}>Now Playing</div>
        <IconBtn name="more" theme={t} color={t.onSurf} onClick={ctx.onOverflow}/>
      </div>

      <div style={{ flex: 1, overflow: 'auto' }}>
        {/* Header */}
        <div style={{ display: 'flex', gap: 14, padding: '4px 20px 14px', alignItems: 'center' }}>
          <div style={{ width: 84, height: 84, borderRadius: 18, background: `linear-gradient(145deg, ${ep.art} 0%, ${ep.art2} 100%)`, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', boxShadow: '0 6px 18px rgba(0,0,0,0.18)' }}>
            <MIcon name="scissors" size={30} color="rgba(255,255,255,0.92)"/>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 12.5, fontWeight: 600, color: t.primary, letterSpacing: 0.3 }}>{ep.show}</div>
            <div style={{ fontSize: 18, fontWeight: 700, color: t.onSurf, lineHeight: 1.18, marginTop: 3, letterSpacing: -0.2 }}>{ep.title}</div>
            <div style={{ fontSize: 12.5, color: t.onSurfMute, marginTop: 5 }}>{ep.meta}</div>
          </div>
        </div>

        {/* Timeline */}
        <Timeline theme={t} ep={ep} playhead={ctx.playhead} onSeek={ctx.onSeek} selectedId={ctx.selectedId} onOpen={ctx.onEdit} suppress={ctx.suppress}/>

        {/* Transport */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 18, padding: '14px 20px 6px' }}>
          <button onClick={ctx.cycleSpeed} style={{ width: 52, height: 40, borderRadius: 20, border: `1px solid ${t.outlineVar}`, background: 'transparent', color: t.onSurfVar, fontFamily: t.font, fontWeight: 700, fontSize: 13, cursor: 'pointer' }}>{ctx.speed}×</button>
          <IconBtn name="rw" theme={t} btn={52} size={28} color={t.onSurf}/>
          <button onClick={ctx.togglePlay} style={{ width: 72, height: 72, borderRadius: 36, border: 'none', background: t.primary, color: t.onPrimary, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', boxShadow: `0 8px 22px ${t.primary}55` }}>
            <MIcon name={ctx.playing ? 'pause' : 'play'} size={34} color={t.onPrimary}/>
          </button>
          <IconBtn name="ff" theme={t} btn={52} size={28} color={t.onSurf}/>
          <div style={{ width: 52, display: 'flex', justifyContent: 'center' }}>
            <div title="Auto-skip on" style={{ width: 40, height: 40, borderRadius: 20, background: t.primaryContainer, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <MIcon name="scissors" size={19} color={t.onPrimaryContainer}/>
            </div>
          </div>
        </div>

        {/* Segment list */}
        <div style={{ marginTop: 18 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', padding: '0 20px 4px', gap: 10 }}>
            <div style={{ fontSize: 16, fontWeight: 700, color: t.onSurf, whiteSpace: 'nowrap' }}>Trimmed in this episode</div>
            <div style={{ fontSize: 13, color: t.onSurfMute, flexShrink: 0 }}>{ep.segments.length}</div>
          </div>
          <div style={{ fontSize: 12.5, color: t.onSurfMute, padding: '0 20px 8px', lineHeight: 1.45 }}>Tap a skip to adjust its edges, or the thumb to confirm it’s right.</div>
          {ep.segments.map((s, i) => (
            <div key={s.id} onClick={() => ctx.onEdit(s)} style={{ background: ctx.selectedId === s.id ? t.scHi : 'transparent', cursor: 'pointer', borderTop: i ? `1px solid ${t.outlineVar}55` : 'none' }}>
              <SegRow theme={t} seg={s} compact={ctx.density === 'compact'} voting={ctx.voting}
                confirmed={ctx.confirmed.has(s.id)}
                onReport={(e) => { e.stopPropagation(); ctx.onReport(s); }}
                onConfirm={(e) => { e.stopPropagation(); ctx.onConfirm(s); }}
                onReplay={(e) => { e.stopPropagation(); ctx.onReplay(s); }}/>
            </div>
          ))}
          <div style={{ padding: '14px 20px 28px' }}>
            <button onClick={ctx.onMissing} style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: '13px 16px', borderRadius: 16, border: `1px dashed ${t.outlineVar}`, background: 'transparent', color: t.onSurfVar, fontFamily: t.font, fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>
              <MIcon name="plus" size={20} color={t.primary}/> Mark a skip we missed
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { EPISODE, WAVE, EpisodeScreen, Timeline, SegRow, confColor });
