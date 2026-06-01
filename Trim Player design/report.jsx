// report.jsx — Report bottom-sheet flow: reason → (fine-tune | duplicate) → success.

// Zoomed, draggable boundary editor (shared by "fine-tune" and "mark missing").
function BoundaryEditor({ theme, win, value, onChange, type, playhead, onScrub }) {
  const t = theme; const c = t.seg[type] || t.seg.ad;
  const ref = useRef(null);
  const span = win.end - win.start;
  const localWave = waveAmps(60, Math.round(win.start) + 3);
  const toX = (tm) => ((tm - win.start) / span) * 100;
  const clamp = (v) => Math.max(win.start, Math.min(win.end, v));

  const seek = (e) => {
    if (!onScrub) return;
    if (e.target.closest('[data-handle]')) return;
    const r = ref.current.getBoundingClientRect();
    const go = (cx) => onScrub(clamp(win.start + ((cx - r.left) / r.width) * span));
    go(e.clientX);
    const move = (ev) => go(ev.clientX);
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };

  const dragHandle = (which) => (e) => {
    e.preventDefault(); e.stopPropagation();
    const r = ref.current.getBoundingClientRect();
    const move = (ev) => {
      const tm = clamp(win.start + ((ev.clientX - r.left) / r.width) * span);
      if (which === 'start') onChange({ start: Math.min(tm, value.end - 1), end: value.end });
      else onChange({ start: value.start, end: Math.max(tm, value.start + 1) });
    };
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };
  const nudge = (which, d) => () => {
    if (which === 'start') onChange({ start: clamp(Math.min(value.start + d, value.end - 1)), end: value.end });
    else onChange({ start: value.start, end: clamp(Math.max(value.end + d, value.start + 1)) });
  };

  const Handle = ({ which }) => (
    <div data-handle onPointerDown={dragHandle(which)} style={{
      position: 'absolute', top: -8, bottom: -8, left: `${toX(which === 'start' ? value.start : value.end)}%`,
      width: 40, transform: 'translateX(-50%)', cursor: 'ew-resize', touchAction: 'none', zIndex: 3,
      display: 'flex', justifyContent: 'center',
    }}>
      <div style={{ width: 3, background: c.solid, borderRadius: 2 }}/>
      <div style={{ position: 'absolute', top: '50%', transform: 'translateY(-50%)', width: 22, height: 30, borderRadius: 8, background: c.solid, display: 'flex', alignItems: 'center', justifyContent: 'center', boxShadow: '0 2px 8px rgba(0,0,0,0.25)' }}>
        <div style={{ width: 2, height: 12, background: 'rgba(255,255,255,0.85)', borderRadius: 1, margin: '0 1px' }}/>
        <div style={{ width: 2, height: 12, background: 'rgba(255,255,255,0.85)', borderRadius: 1, margin: '0 1px' }}/>
      </div>
    </div>
  );

  return (
    <div>
      <div ref={ref} onPointerDown={seek} style={{ position: 'relative', height: 84, margin: '0 6px', display: 'flex', alignItems: 'center', gap: 2, touchAction: 'none', cursor: onScrub ? 'pointer' : 'default' }}>
        {/* selected region tint */}
        <div style={{ position: 'absolute', top: 0, bottom: 0, left: `${toX(value.start)}%`, width: `${toX(value.end) - toX(value.start)}%`, background: c.bg, opacity: t.dark ? 0.5 : 0.8, borderRadius: 6 }}/>
        {localWave.map((a, i) => {
          const tm = win.start + ((i + 0.5) / localWave.length) * span;
          const inside = tm >= value.start && tm < value.end;
          const played = playhead != null && tm <= playhead;
          let bg = inside ? c.solid : t.outlineVar;
          let op = inside ? 1 : 0.5;
          if (played && !inside) { bg = t.onSurfVar; op = 0.6; }
          return <div key={i} style={{ flex: 1, height: `${a * 100}%`, minHeight: 4, borderRadius: 2, background: bg, opacity: op, zIndex: 1 }}/>;
        })}
        {playhead != null && playhead >= win.start && playhead <= win.end && (
          <div style={{ position: 'absolute', left: `${toX(playhead)}%`, top: -6, bottom: -6, width: 2, background: t.onSurf, transform: 'translateX(-50%)', borderRadius: 2, zIndex: 2, pointerEvents: 'none' }}>
            <div style={{ position: 'absolute', top: -4, left: '50%', transform: 'translateX(-50%)', width: 10, height: 10, borderRadius: 5, background: t.onSurf, border: `2px solid ${t.sc}` }}/>
          </div>
        )}
        <Handle which="start"/>
        <Handle which="end"/>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', margin: '6px 6px 0', fontFamily: t.font, fontSize: 11.5, color: t.onSurfMute, fontVariantNumeric: 'tabular-nums' }}>
        <span>{fmt(win.start)}</span><span>{fmt(win.end)}</span>
      </div>
    </div>
  );
}

function NudgeRow({ theme, label, time, delta, onMinus, onPlus }) {
  const t = theme;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <span style={{ width: 44, fontFamily: t.font, fontSize: 13, fontWeight: 600, color: t.onSurfVar }}>{label}</span>
      <span style={{ fontFamily: t.font, fontSize: 15, fontWeight: 700, color: t.onSurf, fontVariantNumeric: 'tabular-nums', width: 52 }}>{fmt(time)}</span>
      {Math.abs(delta) >= 0.05 && <span style={{ fontFamily: t.font, fontSize: 12, fontWeight: 600, color: delta < 0 ? t.warn : t.good, fontVariantNumeric: 'tabular-nums' }}>{fmtDelta(delta)}</span>}
      <div style={{ flex: 1 }}/>
      <IconBtn name="minus" theme={t} btn={38} size={17} bg={t.scHigh} color={t.onSurf} onClick={onMinus} stroke={2.4}/>
      <span style={{ fontFamily: t.font, fontSize: 11, color: t.onSurfMute, width: 16, textAlign: 'center' }}>½s</span>
      <IconBtn name="plus" theme={t} btn={38} size={17} bg={t.scHigh} color={t.onSurf} onClick={onPlus}/>
    </div>
  );
}

const REASONS = [
  { id: 'too-long',  icon: 'long',  title: 'Skipped too much', sub: 'It cut into the episode' },
  { id: 'too-short', icon: 'short', title: 'Skipped too little', sub: 'I still heard part of it' },
  { id: 'wrong',     icon: 'wrong', title: 'Wrong spot',        sub: 'Nothing here needed skipping' },
  { id: 'duplicate', icon: 'dup',   title: 'Duplicate',         sub: 'Another segment already covers this' },
];

function ReasonStep({ theme, seg, onPick }) {
  const t = theme; const m = SEG_META[seg.type]; const c = t.seg[seg.type];
  return (
    <div style={{ padding: '8px 20px 24px', animation: 'mu-fade .2s ease' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
        <Chip icon={m.icon} theme={t} c={c}>{m.label}</Chip>
        <span style={{ fontFamily: t.font, fontSize: 14, color: t.onSurfVar, fontVariantNumeric: 'tabular-nums' }}>{fmt(seg.start)}–{fmt(seg.end)}</span>
      </div>
      <h2 style={{ fontFamily: t.font, fontSize: 22, fontWeight: 700, color: t.onSurf, margin: '10px 0 4px', letterSpacing: -0.3 }}>What’s off with this skip?</h2>
      <p style={{ fontFamily: t.font, fontSize: 13.5, color: t.onSurfMute, margin: '0 0 14px' }}>Your report tunes it for everyone who listens after you.</p>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {REASONS.map(r => (
          <button key={r.id} onClick={() => onPick(r.id)} style={{
            display: 'flex', alignItems: 'center', gap: 14, padding: '12px 14px', borderRadius: 18,
            border: `1px solid ${t.outlineVar}`, background: t.surface, cursor: 'pointer', textAlign: 'left', width: '100%',
            WebkitTapHighlightColor: 'transparent',
          }}>
            <div style={{ width: 44, height: 44, borderRadius: 13, background: t.scHigh, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
              <MIcon name={r.icon} size={22} color={t.primary} stroke={2.1}/>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontFamily: t.font, fontSize: 15.5, fontWeight: 600, color: t.onSurf }}>{r.title}</div>
              <div style={{ fontFamily: t.font, fontSize: 13, color: t.onSurfMute, marginTop: 1 }}>{r.sub}</div>
            </div>
            <MIcon name="chevron" size={20} color={t.onSurfMute}/>
          </button>
        ))}
      </div>
    </div>
  );
}

function FineTuneStep({ theme, seg, reason, bounds, setBounds, onBack, onSubmit }) {
  const t = theme; const c = t.seg[seg.type];
  const pad = Math.max(6, Math.min(20, (seg.end - seg.start) * 0.5));
  const win = { start: Math.max(0, seg.start - pad), end: Math.min(EPISODE.dur, seg.end + pad) };
  const dur = bounds.end - bounds.start;
  return (
    <div style={{ padding: '8px 20px 22px', animation: 'mu-fade .2s ease' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <IconBtn name="back" theme={t} btn={36} size={20} color={t.onSurfVar} onClick={onBack}/>
        <h2 style={{ fontFamily: t.font, fontSize: 20, fontWeight: 700, color: t.onSurf, margin: 0, letterSpacing: -0.3 }}>Drag to fix the edges</h2>
      </div>
      <p style={{ fontFamily: t.font, fontSize: 13.5, color: t.onSurfMute, margin: '0 0 16px 42px' }}>
        {reason === 'too-long' ? 'Pull the edges in so it stops cutting into the show.' : 'Stretch the edges out to cover the whole break.'}
      </p>

      <BoundaryEditor theme={t} win={win} value={bounds} onChange={setBounds} type={seg.type}/>

      <div style={{ display: 'flex', justifyContent: 'center', margin: '12px 0' }}>
        <Chip theme={t} c={{ bg: t.scHigh, fg: t.onSurfVar }}>New length · {fmtDur(dur)}</Chip>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, background: t.scLow, borderRadius: 18, padding: '14px 16px', border: `1px solid ${t.outlineVar}55` }}>
        <NudgeRow theme={t} label="Start" time={bounds.start} delta={bounds.start - seg.start}
          onMinus={() => setBounds(b => ({ ...b, start: Math.max(win.start, b.start - 0.5) }))}
          onPlus={() => setBounds(b => ({ ...b, start: Math.min(b.end - 1, b.start + 0.5) }))}/>
        <div style={{ height: 1, background: t.outlineVar, opacity: 0.5 }}/>
        <NudgeRow theme={t} label="End" time={bounds.end} delta={bounds.end - seg.end}
          onMinus={() => setBounds(b => ({ ...b, end: Math.max(b.start + 1, b.end - 0.5) }))}
          onPlus={() => setBounds(b => ({ ...b, end: Math.min(win.end, b.end + 0.5) }))}/>
      </div>

      <div style={{ marginTop: 18 }}>
        <Btn variant="filled" theme={t} full icon="check" onClick={onSubmit}>Submit fix</Btn>
      </div>
    </div>
  );
}

function DuplicateStep({ theme, seg, onBack, onSubmit }) {
  const t = theme;
  const cand = EPISODE.segments.find(s => s.id !== seg.id && s.type === seg.type) || EPISODE.segments.find(s => s.id !== seg.id);
  const [picked, setPicked] = useState(cand ? cand.id : null);
  const Mini = ({ s, active, onClick }) => {
    const m = SEG_META[s.type]; const c = t.seg[s.type];
    return (
      <button onClick={onClick} style={{
        display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', borderRadius: 16, width: '100%', textAlign: 'left', cursor: 'pointer',
        border: `2px solid ${active ? c.solid : t.outlineVar}`, background: active ? c.bg : t.surface,
      }}>
        <div style={{ width: 40, height: 40, borderRadius: 12, background: c.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}><MIcon name={m.icon} size={20} color={c.fg}/></div>
        <div style={{ flex: 1 }}>
          <div style={{ fontFamily: t.font, fontSize: 14.5, fontWeight: 600, color: t.onSurf }}>{m.label}</div>
          <div style={{ fontFamily: t.font, fontSize: 12.5, color: t.onSurfMute, fontVariantNumeric: 'tabular-nums' }}>{fmt(s.start)}–{fmt(s.end)} · {fmtDur(s.end - s.start)}</div>
        </div>
        {active && <MIcon name="check" size={20} color={c.solid}/>}
      </button>
    );
  };
  return (
    <div style={{ padding: '8px 20px 22px', animation: 'mu-fade .2s ease' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <IconBtn name="back" theme={t} btn={36} size={20} color={t.onSurfVar} onClick={onBack}/>
        <h2 style={{ fontFamily: t.font, fontSize: 20, fontWeight: 700, color: t.onSurf, margin: 0, letterSpacing: -0.3 }}>Which one is the copy?</h2>
      </div>
      <p style={{ fontFamily: t.font, fontSize: 13.5, color: t.onSurfMute, margin: '0 0 16px 42px' }}>We’ll merge the duplicate so this part is only marked once.</p>

      <div style={{ fontFamily: t.font, fontSize: 11.5, fontWeight: 700, letterSpacing: 1, color: t.onSurfMute, textTransform: 'uppercase', margin: '0 0 8px 2px' }}>You’re reporting</div>
      <Mini s={seg} active={false} onClick={() => {}}/>
      <div style={{ textAlign: 'center', fontFamily: t.font, fontSize: 13, color: t.onSurfMute, margin: '12px 0' }}>duplicates ↓</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {EPISODE.segments.filter(s => s.id !== seg.id).map(s => (
          <Mini key={s.id} s={s} active={picked === s.id} onClick={() => setPicked(s.id)}/>
        ))}
      </div>

      <div style={{ marginTop: 18 }}>
        <Btn variant="filled" theme={t} full icon="dup" disabled={!picked} onClick={() => onSubmit(picked)}>Report duplicate</Btn>
      </div>
    </div>
  );
}

function SuccessStep({ theme, summary, voting, onClose }) {
  const t = theme;
  return (
    <div style={{ padding: '20px 24px 30px', textAlign: 'center', animation: 'mu-fade .2s ease' }}>
      <div style={{ width: 72, height: 72, borderRadius: 36, background: t.primaryContainer, display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '6px auto 0', animation: 'mu-check .4s cubic-bezier(.2,.9,.3,1.4)' }}>
        <MIcon name="check" size={38} color={t.onPrimaryContainer} stroke={2.6}/>
      </div>
      <h2 style={{ fontFamily: t.font, fontSize: 22, fontWeight: 700, color: t.onSurf, margin: '16px 0 6px', letterSpacing: -0.3 }}>{summary.title}</h2>
      <p style={{ fontFamily: t.font, fontSize: 14, color: t.onSurfMute, margin: '0 auto', maxWidth: 280, lineHeight: 1.5 }}>{summary.body}</p>

      {voting && summary.pct != null && (
        <div style={{ margin: '20px 0 4px', padding: '14px 16px', borderRadius: 18, background: t.scLow, border: `1px solid ${t.outlineVar}55` }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontFamily: t.font, fontSize: 12.5, color: t.onSurfVar, marginBottom: 8 }}>
            <span>You + {summary.pct}% of listeners agree</span><span style={{ fontWeight: 700, color: t.primary }}>{summary.votes} votes</span>
          </div>
          <div style={{ height: 6, borderRadius: 3, background: t.surfVar, overflow: 'hidden' }}>
            <div style={{ width: `${summary.pct}%`, height: '100%', background: t.primary, borderRadius: 3 }}/>
          </div>
        </div>
      )}

      <div style={{ marginTop: 22 }}>
        <Btn variant="tonal" theme={t} full onClick={onClose}>Done</Btn>
      </div>
    </div>
  );
}

function ReportFlow({ theme, seg, mode, fineTune, voting, onClose, onApply }) {
  const t = theme;
  const [step, setStep] = useState('reason');
  const [reason, setReason] = useState(null);
  const [bounds, setBounds] = useState(seg ? { start: seg.start, end: seg.end } : { start: 0, end: 0 });

  const finish = (summary, apply) => { onApply && apply && onApply(apply); setSummary(summary); setStep('success'); };
  const [summary, setSummary] = useState({ title: '', body: '' });

  const pick = (id) => {
    setReason(id);
    if (id === 'duplicate') { setStep('duplicate'); return; }
    if ((id === 'too-long' || id === 'too-short')) {
      setBounds({ start: seg.start, end: seg.end });
      if (fineTune) { setStep('finetune'); return; }
      finish({
        title: 'Thanks — noted!',
        body: id === 'too-long' ? 'We’ll tighten this skip for everyone.' : 'We’ll extend this skip for everyone.',
        pct: id === 'too-long' ? 84 : 76, votes: seg.votes + 1,
      });
      return;
    }
    // wrong spot
    finish({ title: 'Got it — flagged', body: 'We’ll review whether anything should be skipped here.', pct: 68, votes: seg.votes + 1 });
  };

  const submitFine = () => {
    const d = (bounds.end - bounds.start) - (seg.end - seg.start);
    finish({
      title: 'Fix submitted',
      body: `Your ${fmtDur(Math.abs(d))} ${d < 0 ? 'tighter' : 'longer'} edit is live for review. Thanks for sharpening it!`,
      pct: 81, votes: seg.votes + 1,
    }, { id: seg.id, ...bounds });
  };

  const submitDup = (otherId) => {
    finish({ title: 'Duplicate merged', body: 'This part is now marked just once. Nice catch.', pct: 73, votes: seg.votes + 1 });
  };

  return (
    <Sheet open={true} onClose={onClose} theme={t}>
      <div style={{ overflowY: 'auto' }}>
        {step === 'reason'   && <ReasonStep theme={t} seg={seg} onPick={pick}/>}
        {step === 'finetune' && <FineTuneStep theme={t} seg={seg} reason={reason} bounds={bounds} setBounds={(u) => setBounds(typeof u === 'function' ? u : u)} onBack={() => setStep('reason')} onSubmit={submitFine}/>}
        {step === 'duplicate'&& <DuplicateStep theme={t} seg={seg} onBack={() => setStep('reason')} onSubmit={submitDup}/>}
        {step === 'success'  && <SuccessStep theme={t} summary={summary} voting={voting} onClose={onClose}/>}
      </div>
    </Sheet>
  );
}

// ── Edit an existing segment (drag boundaries / relabel / remove / dedupe) ──
function EditSegmentFlow({ theme, seg, voting, onClose, onApply, onReplay }) {
  const t = theme;
  const [step, setStep] = useState('edit');
  const [type, setType] = useState(seg.type);
  const [bounds, setBounds] = useState({ start: seg.start, end: seg.end });
  const [summary, setSummary] = useState({ title: '', body: '' });
  const pad = Math.max(6, Math.min(20, (seg.end - seg.start) * 0.5));
  const win = { start: Math.max(0, seg.start - pad), end: Math.min(EPISODE.dur, seg.end + pad) };
  const dur = bounds.end - bounds.start;

  // Local preview playback (sweeps a playhead across the zoomed window).
  const [pp, setPp] = useState(seg.start);
  const [playing, setPlaying] = useState(false);
  const ppRef = useRef(pp);
  useEffect(() => { ppRef.current = pp; }, [pp]);
  useEffect(() => {
    if (!playing) return;
    const iv = setInterval(() => {
      let np = ppRef.current + 0.12;
      if (np >= win.end) { np = win.end; setPlaying(false); }
      ppRef.current = np; setPp(np);
    }, 100);
    return () => clearInterval(iv);
  }, [playing, win.end]);
  const togglePreview = () => {
    if (playing) { setPlaying(false); return; }
    if (ppRef.current >= win.end - 0.05) setPp(win.start);
    setPlaying(true);
  };
  const scrub = (tm) => { setPlaying(false); setPp(tm); };
  const types = ['ad', 'sponsor', 'intro', 'silence'];
  const moved = Math.abs(bounds.start - seg.start) > 0.05 || Math.abs(bounds.end - seg.end) > 0.05;
  const changed = moved || type !== seg.type;
  const c = t.seg[type]; const m = SEG_META[type];

  const save = () => {
    const d = dur - (seg.end - seg.start);
    setSummary({
      title: 'Changes saved',
      body: moved ? `Your ${fmtDur(Math.abs(d))} ${d < 0 ? 'tighter' : 'longer'} edit is live for review. Thanks for sharpening it!` : 'Updated the label for everyone. Thanks!',
      pct: 81, votes: seg.votes + 1,
    });
    onApply({ action: 'save', id: seg.id, start: bounds.start, end: bounds.end, segType: type });
    setStep('success');
  };
  const remove = () => {
    setSummary({ title: 'Skip removed', body: 'We’ll stop skipping here once a few others agree. Nice catch.', pct: 68, votes: seg.votes + 1 });
    onApply({ action: 'remove', id: seg.id });
    setStep('success');
  };
  const submitDup = () => {
    setSummary({ title: 'Duplicate merged', body: 'This part is now marked just once. Nice catch.', pct: 73, votes: seg.votes + 1 });
    onApply({ action: 'remove', id: seg.id });
    setStep('success');
  };

  return (
    <Sheet open={true} onClose={onClose} theme={t}>
      <div style={{ overflowY: 'auto' }}>
        {step === 'edit' && (
          <div style={{ padding: '8px 20px 22px', animation: 'mu-fade .2s ease' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <h2 style={{ fontFamily: t.font, fontSize: 21, fontWeight: 700, color: t.onSurf, margin: '6px 0 2px', letterSpacing: -0.3 }}>Edit this skip</h2>
              <div style={{ flex: 1 }}/>
              <IconBtn name="close" theme={t} btn={38} size={20} color={t.onSurfVar} onClick={onClose}/>
            </div>
            <p style={{ fontFamily: t.font, fontSize: 13.5, color: t.onSurfMute, margin: '0 0 16px' }}>Play it back and drag the edges to fix where it starts and ends, change the label, or remove it.</p>

            <BoundaryEditor theme={t} win={win} value={bounds} onChange={setBounds} type={type} playhead={pp} onScrub={scrub}/>

            <div style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '12px 2px 0' }}>
              <button onClick={togglePreview} style={{ width: 46, height: 46, borderRadius: 23, background: t.primary, color: t.onPrimary, border: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', flexShrink: 0, boxShadow: `0 4px 14px ${t.primary}44` }}>
                <MIcon name={playing ? 'pause' : 'play'} size={22} color={t.onPrimary}/>
              </button>
              <button onClick={() => scrub(bounds.start)} title="Jump to start of skip" style={{ width: 40, height: 40, borderRadius: 20, border: `1px solid ${t.outlineVar}`, background: 'transparent', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', flexShrink: 0 }}>
                <MIcon name="replay" size={18} color={t.onSurfVar}/>
              </button>
              <span style={{ fontFamily: t.font, fontSize: 13, color: t.onSurfVar, fontVariantNumeric: 'tabular-nums' }}>{fmt(pp)} <span style={{ color: t.onSurfMute }}>/ {fmt(win.end)}</span></span>
              <div style={{ flex: 1 }}/>
              <Chip theme={t} c={{ bg: t.scHigh, fg: t.onSurfVar }}>{fmtDur(dur)}</Chip>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, background: t.scLow, borderRadius: 18, padding: '14px 16px', border: `1px solid ${t.outlineVar}55` }}>
              <NudgeRow theme={t} label="Start" time={bounds.start} delta={bounds.start - seg.start}
                onMinus={() => setBounds(b => ({ ...b, start: Math.max(win.start, b.start - 0.5) }))}
                onPlus={() => setBounds(b => ({ ...b, start: Math.min(b.end - 1, b.start + 0.5) }))}/>
              <div style={{ height: 1, background: t.outlineVar, opacity: 0.5 }}/>
              <NudgeRow theme={t} label="End" time={bounds.end} delta={bounds.end - seg.end}
                onMinus={() => setBounds(b => ({ ...b, end: Math.max(b.start + 1, b.end - 0.5) }))}
                onPlus={() => setBounds(b => ({ ...b, end: Math.min(win.end, b.end + 0.5) }))}/>
            </div>

            <div style={{ fontFamily: t.font, fontSize: 11.5, fontWeight: 700, letterSpacing: 1, color: t.onSurfMute, textTransform: 'uppercase', margin: '18px 0 8px 2px' }}>Label</div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {types.map(ty => {
                const cc = t.seg[ty]; const mm = SEG_META[ty]; const on = type === ty;
                return (
                  <button key={ty} onClick={() => setType(ty)} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 14px', borderRadius: 100, cursor: 'pointer', border: `1.5px solid ${on ? cc.solid : t.outlineVar}`, background: on ? cc.bg : 'transparent', color: on ? cc.fg : t.onSurfVar, fontFamily: t.font, fontWeight: 600, fontSize: 13.5 }}>
                    <MIcon name={mm.icon} size={16} color={on ? cc.fg : t.onSurfVar}/> {mm.label}
                  </button>
                );
              })}
            </div>

            <div style={{ marginTop: 20 }}>
              <Btn variant="filled" theme={t} full icon="check" disabled={!changed} onClick={save}>Save changes</Btn>
            </div>

            <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
              <button onClick={() => setStep('duplicate')} style={{ flex: 1, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8, height: 46, borderRadius: 23, border: `1px solid ${t.outlineVar}`, background: 'transparent', color: t.onSurfVar, fontFamily: t.font, fontWeight: 600, fontSize: 13.5, cursor: 'pointer' }}>
                <MIcon name="dup" size={18} color={t.onSurfVar}/> Duplicate
              </button>
              <button onClick={remove} style={{ flex: 1, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8, height: 46, borderRadius: 23, border: `1px solid ${t.outlineVar}`, background: 'transparent', color: t.warn, fontFamily: t.font, fontWeight: 600, fontSize: 13.5, cursor: 'pointer' }}>
                <MIcon name="wrong" size={18} color={t.warn}/> Not a skip
              </button>
            </div>
          </div>
        )}
        {step === 'duplicate' && <DuplicateStep theme={t} seg={seg} onBack={() => setStep('edit')} onSubmit={submitDup}/>}
        {step === 'success'   && <SuccessStep theme={t} summary={summary} voting={voting} onClose={onClose}/>}
      </div>
    </Sheet>
  );
}

Object.assign(window, { ReportFlow, EditSegmentFlow, BoundaryEditor, ReasonStep, FineTuneStep, DuplicateStep, SuccessStep });
