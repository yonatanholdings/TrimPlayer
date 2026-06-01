// proto.jsx — Phone shell, playback sim, toast, overflow, missing-flow, tweaks.

const KEY_OF = { '#1f6e60': 'teal', '#4456b3': 'indigo', '#bb3f2c': 'coral' };
const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "dark": false,
  "accent": "#1f6e60",
  "autoToast": true,
  "fineTune": true,
  "voting": true,
  "density": "comfy"
}/*EDITMODE-END*/;

// ── Status bar / nav pill ───────────────────────────────────────────────────
function StatusBar({ theme }) {
  const c = theme.onSurf;
  return (
    <div style={{ height: 36, display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 22px', flexShrink: 0 }}>
      <span style={{ fontFamily: theme.font, fontSize: 14, fontWeight: 600, color: c, letterSpacing: 0.2 }}>9:30</span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <svg width="17" height="13" viewBox="0 0 17 13"><path d="M8.5 12L.6 4.1a11 11 0 0115.8 0L8.5 12z" fill={c}/></svg>
        <svg width="16" height="13" viewBox="0 0 16 13"><rect x="1" y="2" width="13" height="9" rx="2" fill="none" stroke={c} strokeWidth="1.4"/><rect x="2.6" y="3.6" width="9" height="5.8" rx="1" fill={c}/><rect x="14.4" y="4.6" width="1.4" height="3.8" rx="0.7" fill={c}/></svg>
      </div>
    </div>
  );
}

// ── Snackbar / toast ────────────────────────────────────────────────────────
function Toast({ theme, toast, onUndo, onReport, onClose }) {
  const [render, shown] = useMount(!!toast, 240);
  const t = theme;
  if (!render || !toast) return null;
  const m = SEG_META[toast.seg.type]; const c = t.seg[toast.seg.type];
  const bg = t.dark ? '#e6ece9' : '#2c3331', fg = t.dark ? '#1a201e' : '#f1f4f2';
  return (
    <div style={{
      position: 'absolute', left: 12, right: 12, bottom: 34, zIndex: 55,
      transform: shown ? 'translateY(0)' : 'translateY(140%)', opacity: shown ? 1 : 0,
      transition: 'transform .28s cubic-bezier(.2,.8,.2,1), opacity .2s',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, background: bg, borderRadius: 16, padding: '10px 8px 10px 14px', boxShadow: '0 8px 28px rgba(0,0,0,0.3)' }}>
        <div style={{ width: 30, height: 30, borderRadius: 9, background: c.solid, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
          <MIcon name="scissors" size={17} color="#fff"/>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontFamily: t.font, fontSize: 14, fontWeight: 600, color: fg }}>Skipped {fmtDur(toast.seg.end - toast.seg.start)} {m.label.toLowerCase()}</div>
          <div style={{ fontFamily: t.font, fontSize: 12, color: fg, opacity: 0.7 }}>Was that right?</div>
        </div>
        <button onClick={onUndo} style={{ height: 36, padding: '0 12px', borderRadius: 18, border: 'none', background: 'transparent', color: t.dark ? t.primary : '#9fe0d2', fontFamily: t.font, fontWeight: 600, fontSize: 13.5, cursor: 'pointer' }}>Undo</button>
        <button onClick={onReport} style={{ height: 36, padding: '0 14px', borderRadius: 18, border: 'none', background: c.solid, color: '#fff', fontFamily: t.font, fontWeight: 700, fontSize: 13.5, cursor: 'pointer' }}>Report</button>
      </div>
    </div>
  );
}

// ── Overflow menu ───────────────────────────────────────────────────────────
function Overflow({ theme, onPick, onClose }) {
  const t = theme;
  const items = [{ id: 'report', icon: 'flag', label: 'Report a skip' }, { id: 'missing', icon: 'plus', label: 'Mark a missed skip' }];
  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 58 }} onClick={onClose}>
      <div onClick={e => e.stopPropagation()} style={{ position: 'absolute', top: 46, right: 10, minWidth: 210, background: t.scHigh, borderRadius: 16, padding: 8, boxShadow: '0 10px 34px rgba(0,0,0,0.28)', animation: 'mu-pop .16s ease' }}>
        {items.map(it => (
          <button key={it.id} onClick={() => onPick(it.id)} style={{ display: 'flex', alignItems: 'center', gap: 14, width: '100%', padding: '12px 12px', borderRadius: 10, border: 'none', background: 'transparent', cursor: 'pointer', fontFamily: t.font, fontSize: 14.5, color: t.onSurf, textAlign: 'left' }}>
            <MIcon name={it.icon} size={20} color={t.onSurfVar}/> {it.label}
          </button>
        ))}
      </div>
    </div>
  );
}

// ── Mark-missing flow ───────────────────────────────────────────────────────
function MissingFlow({ theme, playhead, onClose }) {
  const t = theme;
  const [step, setStep] = useState('edit');
  const [type, setType] = useState('ad');
  const winLen = 44;
  const win = { start: Math.max(0, playhead - winLen / 2), end: Math.min(EPISODE.dur, Math.max(playhead + winLen / 2, winLen)) };
  const [bounds, setBounds] = useState({ start: win.start + winLen * 0.32, end: win.start + winLen * 0.62 });
  const types = ['ad', 'sponsor', 'intro', 'silence'];
  return (
    <Sheet open={true} onClose={onClose} theme={t}>
      <div style={{ overflowY: 'auto' }}>
        {step === 'edit' ? (
          <div style={{ padding: '8px 20px 22px', animation: 'mu-fade .2s ease' }}>
            <h2 style={{ fontFamily: t.font, fontSize: 21, fontWeight: 700, color: t.onSurf, margin: '6px 0 4px', letterSpacing: -0.3 }}>Mark a skip we missed</h2>
            <p style={{ fontFamily: t.font, fontSize: 13.5, color: t.onSurfMute, margin: '0 0 16px' }}>Set the part that should be skipped, then tell us what it is.</p>
            <BoundaryEditor theme={t} win={win} value={bounds} onChange={setBounds} type={type}/>
            <div style={{ display: 'flex', justifyContent: 'center', margin: '12px 0 16px' }}>
              <Chip theme={t} c={{ bg: t.scHigh, fg: t.onSurfVar }}>{fmt(bounds.start)}–{fmt(bounds.end)} · {fmtDur(bounds.end - bounds.start)}</Chip>
            </div>
            <div style={{ fontFamily: t.font, fontSize: 11.5, fontWeight: 700, letterSpacing: 1, color: t.onSurfMute, textTransform: 'uppercase', margin: '0 0 8px 2px' }}>What is it?</div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {types.map(ty => {
                const c = t.seg[ty]; const m = SEG_META[ty]; const on = type === ty;
                return (
                  <button key={ty} onClick={() => setType(ty)} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 14px', borderRadius: 100, cursor: 'pointer', border: `1.5px solid ${on ? c.solid : t.outlineVar}`, background: on ? c.bg : 'transparent', color: on ? c.fg : t.onSurfVar, fontFamily: t.font, fontWeight: 600, fontSize: 13.5 }}>
                    <MIcon name={m.icon} size={16} color={on ? c.fg : t.onSurfVar}/> {m.label}
                  </button>
                );
              })}
            </div>
            <div style={{ marginTop: 20 }}><Btn variant="filled" theme={t} full icon="plus" onClick={() => setStep('done')}>Add this skip</Btn></div>
          </div>
        ) : (
          <SuccessStep theme={t} voting={true} summary={{ title: 'Skip added', body: 'Thanks! Once a few others confirm it, we’ll skip it automatically for everyone.', pct: 64, votes: 7 }} onClose={onClose}/>
        )}
      </div>
    </Sheet>
  );
}

// ── App ─────────────────────────────────────────────────────────────────────
function App() {
  const [tw, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const theme = makeTheme(tw.dark, KEY_OF[tw.accent] || 'teal');
  const t = theme;

  const [ep, setEp] = useState(EPISODE);
  const [playhead, setPlayhead] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1.5);
  const [selectedId, setSelected] = useState(null);
  const [confirmed, setConfirmed] = useState(new Set());
  const [toast, setToast] = useState(null);
  const [reportSeg, setReportSeg] = useState(null);
  const [editSeg, setEditSeg] = useState(null);
  const [overflow, setOverflow] = useState(false);
  const [missing, setMissing] = useState(false);

  const phRef = useRef(0); const suppressRef = useRef(null); const toastTimer = useRef(null);
  useEffect(() => { phRef.current = playhead; }, [playhead]);

  const showToast = (seg) => {
    setToast({ seg, ts: Date.now() });
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 6000);
  };

  // playback loop
  useEffect(() => {
    if (!playing) return;
    const iv = setInterval(() => {
      let np = phRef.current + speed * 2.4;
      const sup = suppressRef.current;
      if (sup) { const ss = ep.segments.find(s => s.id === sup); if (ss && np >= ss.end) suppressRef.current = null; }
      const seg = ep.segments.find(s => np >= s.start && np < s.end);
      if (seg && suppressRef.current !== seg.id) {
        np = seg.end;
        if (tw.autoToast) showToast(seg);
        else { setSelected(seg.id); }
      }
      if (np >= ep.dur) { np = ep.dur; setPlaying(false); }
      phRef.current = np; setPlayhead(np);
    }, 100);
    return () => clearInterval(iv);
  }, [playing, speed, ep, tw.autoToast]);

  const togglePlay = () => setPlaying(p => !p);
  const onSeek = (s) => { suppressRef.current = (ep.segments.find(g => s >= g.start && s < g.end) || {}).id || null; phRef.current = s; setPlayhead(s); };
  const replay = (seg) => { suppressRef.current = seg.id; phRef.current = seg.start; setPlayhead(seg.start); setPlaying(true); setSelected(seg.id); setToast(null); };
  const undo = () => { if (!toast) return; const seg = toast.seg; suppressRef.current = seg.id; phRef.current = seg.start; setPlayhead(seg.start); setToast(null); };
  const confirm = (seg) => { setConfirmed(s => new Set(s).add(seg.id)); };
  const openReport = (seg) => { setToast(null); setOverflow(false); setReportSeg(seg); };
  const openEdit = (seg) => { setToast(null); setOverflow(false); setPlaying(false); setSelected(seg.id); setEditSeg(seg); };
  const applyFix = (fix) => setEp(e => ({ ...e, segments: e.segments.map(s => s.id === fix.id ? { ...s, start: fix.start, end: fix.end, conf: Math.min(0.99, s.conf + 0.12), votes: s.votes + 1 } : s) }));
  const applyEdit = (a) => {
    if (a.action === 'remove') setEp(e => ({ ...e, segments: e.segments.filter(s => s.id !== a.id) }));
    else if (a.action === 'save') setEp(e => ({ ...e, segments: e.segments.map(s => s.id === a.id ? { ...s, start: a.start, end: a.end, type: a.segType, conf: Math.min(0.99, s.conf + 0.1), votes: s.votes + 1 } : s) }));
  };

  const ctx = {
    playhead, playing, togglePlay, onSeek, speed, cycleSpeed: () => setSpeed(s => ({ 1: 1.2, 1.2: 1.5, 1.5: 1.8, 1.8: 2, 2: 1 }[s] || 1.5)),
    selectedId, setSelected, suppress: suppressRef.current,
    confirmed, onConfirm: confirm, onReplay: replay, onReport: openReport, onEdit: openEdit,
    onOverflow: () => setOverflow(true), onMissing: () => { setSelected(null); setMissing(true); },
    density: tw.density, voting: tw.voting,
  };

  // scale to fit
  const [scale, setScale] = useState(1);
  useLayoutEffect(() => {
    const fit = () => setScale(Math.min(1, (window.innerWidth - 28) / 400, (window.innerHeight - 28) / 860));
    fit(); window.addEventListener('resize', fit); return () => window.removeEventListener('resize', fit);
  }, []);

  return (
    <div style={{ position: 'fixed', inset: 0, background: t.dark ? '#0a0f0e' : '#cfd8d4', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}>
      <div style={{ transform: `scale(${scale})`, transformOrigin: 'center' }}>
        <div style={{
          width: 400, height: 860, borderRadius: 44, overflow: 'hidden', position: 'relative',
          background: t.surface, boxShadow: '0 40px 90px rgba(0,0,0,0.4), 0 0 0 10px #11181666',
          display: 'flex', flexDirection: 'column',
        }}>
          <StatusBar theme={t}/>
          <div style={{ flex: 1, minHeight: 0 }}>
            <EpisodeScreen theme={t} ep={ep} ctx={ctx}/>
          </div>
          <div style={{ height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <div style={{ width: 128, height: 5, borderRadius: 3, background: t.onSurf, opacity: 0.35 }}/>
          </div>

          <Toast theme={t} toast={toast} onUndo={undo} onReport={() => openReport(toast.seg)} onClose={() => setToast(null)}/>
          {overflow && <Overflow theme={t} onClose={() => setOverflow(false)} onPick={(id) => { setOverflow(false); if (id === 'missing') setMissing(true); else openReport(selectedId ? ep.segments.find(s => s.id === selectedId) : ep.segments[1]); }}/>}
          {reportSeg && <ReportFlow theme={t} seg={reportSeg} fineTune={tw.fineTune} voting={tw.voting} onApply={applyFix} onClose={() => setReportSeg(null)}/>}
          {editSeg && <EditSegmentFlow theme={t} seg={ep.segments.find(s => s.id === editSeg.id) || editSeg} voting={tw.voting} onApply={applyEdit} onReplay={replay} onClose={() => { setEditSeg(null); setSelected(null); }}/>}
          {missing && <MissingFlow theme={t} playhead={playhead} onClose={() => setMissing(false)}/>}
        </div>
      </div>

      <TweaksPanel title="Tweaks">
        <TweakSection label="Appearance">
          <TweakToggle label="Dark mode" value={tw.dark} onChange={v => setTweak('dark', v)}/>
          <TweakColor label="Accent" value={tw.accent} options={['#1f6e60', '#4456b3', '#bb3f2c']} onChange={v => setTweak('accent', v)}/>
          <TweakRadio label="Density" value={tw.density} options={['comfy', 'compact']} onChange={v => setTweak('density', v)}/>
        </TweakSection>
        <TweakSection label="Reporting flow">
          <TweakToggle label="Auto-skip toast" value={tw.autoToast} onChange={v => setTweak('autoToast', v)}/>
          <TweakToggle label="Boundary fine-tune step" value={tw.fineTune} onChange={v => setTweak('fineTune', v)}/>
          <TweakToggle label="Community voting" value={tw.voting} onChange={v => setTweak('voting', v)}/>
        </TweakSection>
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
