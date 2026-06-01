// mui.jsx — Material You theme tokens, icon set, primitives, waveform.
const { useState, useEffect, useRef, useLayoutEffect } = React;

// ── Theme ────────────────────────────────────────────────────────────────
const ACCENTS = {
  teal:   { l: { p:'#1f6e60', op:'#ffffff', pc:'#a4f0dd', opc:'#00201a' }, d: { p:'#84d6c6', op:'#003730', pc:'#00504a', opc:'#a4f0dd' } },
  indigo: { l: { p:'#4456b3', op:'#ffffff', pc:'#dfe1ff', opc:'#00105c' }, d: { p:'#bcc2ff', op:'#142678', pc:'#2c3da0', opc:'#dfe1ff' } },
  coral:  { l: { p:'#bb3f2c', op:'#ffffff', pc:'#ffdad2', opc:'#410100' }, d: { p:'#ffb4a4', op:'#5f150a', pc:'#852d1d', opc:'#ffdad2' } },
};
const ACCENT_HEX = { teal: '#1f6e60', indigo: '#4456b3', coral: '#bb3f2c' };

const SEG_L = {
  ad:      { fg:'#8a5200', bg:'#ffdca8', solid:'#c77800' },
  sponsor: { fg:'#7b3f9d', bg:'#f0dbff', solid:'#9a52c4' },
  intro:   { fg:'#3a4ba0', bg:'#dfe1ff', solid:'#5566cc' },
  silence: { fg:'#4f5450', bg:'#e0e4e0', solid:'#7a807b' },
};
const SEG_D = {
  ad:      { fg:'#ffce8a', bg:'#5a3a00', solid:'#e0a23c' },
  sponsor: { fg:'#e9c2ff', bg:'#4a2b63', solid:'#c79ae0' },
  intro:   { fg:'#c3c8ff', bg:'#2d3a86', solid:'#9aa6f0' },
  silence: { fg:'#cfd4cf', bg:'#363b37', solid:'#9aa09b' },
};
const SEG_META = {
  ad:      { label: 'Ad break', icon: 'ad' },
  sponsor: { label: 'Sponsor',  icon: 'tag' },
  intro:   { label: 'Intro',    icon: 'flag' },
  silence: { label: 'Silence',  icon: 'quiet' },
};

function makeTheme(dark, accentKey = 'teal') {
  const a = ACCENTS[accentKey][dark ? 'd' : 'l'];
  const light = {
    surface:'#f6faf8', sc:'#f0f4f1', scLow:'#f0f4f1', scHi:'#e7ece9', scHigh:'#e1e7e3',
    surfVar:'#dbe5e0', onSurf:'#161d1b', onSurfVar:'#3f4946', onSurfMute:'#6b756f',
    outline:'#6f7975', outlineVar:'#c4cdc8', scrim:'rgba(8,14,12,0.42)',
    good:'#2f6c4f', goodBg:'#c2efd6', warn:'#9a4a1f',
  };
  const dk = {
    surface:'#0e1513', sc:'#161d1b', scLow:'#13191780', scHi:'#1d2422', scHigh:'#242c2a',
    surfVar:'#3f4946', onSurf:'#dde4e1', onSurfVar:'#bfc9c4', onSurfMute:'#8b958f',
    outline:'#899390', outlineVar:'#3f4946', scrim:'rgba(0,0,0,0.55)',
    good:'#7fd6a0', goodBg:'#1e3b2c', warn:'#ffb68a',
  };
  const base = dark ? dk : light;
  return {
    dark, font: "'Roboto', system-ui, sans-serif",
    primary:a.p, onPrimary:a.op, primaryContainer:a.pc, onPrimaryContainer:a.opc,
    seg: dark ? SEG_D : SEG_L,
    ...base,
  };
}

// ── Icons ────────────────────────────────────────────────────────────────
function MIcon({ name, size = 24, color = 'currentColor', stroke = 2, fill = false }) {
  const p = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth: stroke, strokeLinecap: 'round', strokeLinejoin: 'round' };
  switch (name) {
    case 'back':    return <svg {...p}><path d="M15 5l-7 7 7 7"/></svg>;
    case 'more':    return <svg {...p}><circle cx="12" cy="5" r="1.4" fill={color} stroke="none"/><circle cx="12" cy="12" r="1.4" fill={color} stroke="none"/><circle cx="12" cy="19" r="1.4" fill={color} stroke="none"/></svg>;
    case 'play':    return <svg {...p}><path d="M7 4.5l13 7.5-13 7.5z" fill={color} stroke="none"/></svg>;
    case 'pause':   return <svg {...p}><rect x="6" y="5" width="4" height="14" rx="1.2" fill={color} stroke="none"/><rect x="14" y="5" width="4" height="14" rx="1.2" fill={color} stroke="none"/></svg>;
    case 'rw':      return <svg {...p}><path d="M11 8H6.5M6.5 8l3-3M6.5 8l3 3"/><path d="M7 12a7 7 0 107-7" /><text x="13" y="20" fontSize="7" fill={color} stroke="none" fontFamily="Roboto" fontWeight="700">10</text></svg>;
    case 'ff':      return <svg {...p}><path d="M13 8h4.5M17.5 8l-3-3M17.5 8l-3 3"/><path d="M17 12a7 7 0 11-7-7"/><text x="6.5" y="20" fontSize="7" fill={color} stroke="none" fontFamily="Roboto" fontWeight="700">30</text></svg>;
    case 'flag':    return <svg {...p}><path d="M5 21V4M5 4h11l-2 4 2 4H5"/></svg>;
    case 'ad':      return <svg {...p}><rect x="3" y="6" width="18" height="12" rx="2.5"/><path d="M7.5 15l2-6 2 6M8 13h3"/><path d="M14.5 15V9h1.8a1.8 1.8 0 010 3.6h-1.8"/></svg>;
    case 'tag':     return <svg {...p}><path d="M4 4h7l9 9-7 7-9-9z"/><circle cx="8" cy="8" r="1.3" fill={color} stroke="none"/></svg>;
    case 'quiet':   return <svg {...p}><path d="M4 9v6h4l5 4V5L8 9z" fill={fill?color:'none'}/><path d="M17 9l4 6M21 9l-4 6"/></svg>;
    case 'chevron': return <svg {...p}><path d="M9 6l6 6-6 6"/></svg>;
    case 'close':   return <svg {...p}><path d="M6 6l12 12M18 6L6 18"/></svg>;
    case 'check':   return <svg {...p}><path d="M5 12.5l4.5 4.5L19 6.5"/></svg>;
    case 'thumb':   return <svg {...p}><path d="M7 11v9H4a1 1 0 01-1-1v-7a1 1 0 011-1h3zm0 0l4-7a2 2 0 012 2l-1 5h5.5a2 2 0 011.9 2.6l-1.7 6A2 2 0 0117.8 20H7"/></svg>;
    case 'dup':     return <svg {...p}><rect x="8" y="8" width="12" height="12" rx="2.5"/><path d="M16 8V5.5A1.5 1.5 0 0014.5 4H5.5A1.5 1.5 0 004 5.5v9A1.5 1.5 0 005.5 16H8"/></svg>;
    case 'long':    return <svg {...p}><path d="M9 7L5 12l4 5M15 7l4 5-4 5M5 12h14"/></svg>;
    case 'short':   return <svg {...p}><path d="M6 7l4 5-4 5M18 7l-4 5 4 5M8 12h8"/></svg>;
    case 'wrong':   return <svg {...p}><circle cx="12" cy="12" r="9"/><path d="M9 9l6 6M15 9l-6 6"/></svg>;
    case 'plus':    return <svg {...p}><path d="M12 5v14M5 12h14"/></svg>;
    case 'minus':   return <svg {...p}><path d="M5 12h14"/></svg>;
    case 'undo':    return <svg {...p}><path d="M9 7L4 12l5 5M4 12h11a5 5 0 010 10h-1"/></svg>;
    case 'scissors':return <svg {...p}><circle cx="6" cy="7" r="2.4"/><circle cx="6" cy="17" r="2.4"/><path d="M8 8.5l12 7M8 15.5l12-7"/></svg>;
    case 'speed':   return <svg {...p}><path d="M12 14l4-4M5.5 18a9 9 0 1113 0"/><circle cx="12" cy="14" r="1.3" fill={color} stroke="none"/></svg>;
    case 'replay':  return <svg {...p}><path d="M4 5v5h5"/><path d="M4 10a8 8 0 113 8" /></svg>;
    default:        return null;
  }
}

// ── Buttons ────────────────────────────────────────────────────────────────
function Btn({ children, variant = 'filled', theme, onClick, icon, full = false, disabled = false, tone, style = {} }) {
  const t = theme;
  const map = {
    filled:   { bg: tone || t.primary, fg: tone ? t.onPrimary : t.onPrimary, bd: 'none' },
    tonal:    { bg: t.primaryContainer, fg: t.onPrimaryContainer, bd: 'none' },
    outlined: { bg: 'transparent', fg: t.primary, bd: `1px solid ${t.outlineVar}` },
    text:     { bg: 'transparent', fg: t.primary, bd: 'none' },
    surface:  { bg: t.scHigh, fg: t.onSurf, bd: 'none' },
  }[variant];
  return (
    <button onClick={disabled ? undefined : onClick} style={{
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
      height: 52, padding: icon ? '0 22px 0 18px' : '0 24px', borderRadius: 26,
      background: map.bg, color: map.fg, border: map.bd, cursor: disabled ? 'default' : 'pointer',
      fontFamily: t.font, fontSize: 15, fontWeight: 600, letterSpacing: 0.1,
      width: full ? '100%' : 'auto', opacity: disabled ? 0.4 : 1, flexShrink: 0,
      transition: 'filter .15s, transform .08s', WebkitTapHighlightColor: 'transparent',
      ...style,
    }}
    onMouseDown={e => { if (!disabled) e.currentTarget.style.transform = 'scale(0.97)'; }}
    onMouseUp={e => e.currentTarget.style.transform = 'scale(1)'}
    onMouseLeave={e => e.currentTarget.style.transform = 'scale(1)'}>
      {icon && <MIcon name={icon} size={20} color={map.fg}/>}
      {children}
    </button>
  );
}

function IconBtn({ name, theme, onClick, size = 24, btn = 44, color, bg = 'transparent', stroke = 2, fill = false }) {
  return (
    <button onClick={onClick} style={{
      width: btn, height: btn, borderRadius: btn / 2, border: 'none', background: bg,
      display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
      flexShrink: 0, WebkitTapHighlightColor: 'transparent', padding: 0,
    }}>
      <MIcon name={name} size={size} color={color || theme.onSurfVar} stroke={stroke} fill={fill}/>
    </button>
  );
}

function Chip({ icon, children, theme, c, tonal = true, small = false }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5,
      padding: small ? '3px 9px 3px 7px' : '5px 11px 5px 9px', borderRadius: 100,
      background: tonal ? c.bg : 'transparent', color: c.fg,
      border: tonal ? 'none' : `1px solid ${c.solid}`,
      fontFamily: theme.font, fontSize: small ? 11.5 : 12.5, fontWeight: 600, letterSpacing: 0.2,
      whiteSpace: 'nowrap',
    }}>
      {icon && <MIcon name={icon} size={small ? 13 : 15} color={c.fg} stroke={2.2}/>}
      {children}
    </span>
  );
}

// ── Sheet (modal bottom sheet w/ enter/exit) ────────────────────────────────
function useMount(open, ms = 280) {
  const [render, setRender] = useState(open);
  const [shown, setShown] = useState(false);
  useEffect(() => {
    let on, off;
    if (open) { setRender(true); on = setTimeout(() => setShown(true), 20); }
    else { setShown(false); off = setTimeout(() => setRender(false), ms); }
    return () => { clearTimeout(on); clearTimeout(off); };
  }, [open]);
  return [render, shown];
}

function Sheet({ open, onClose, theme, children, maxH = '88%' }) {
  const [render, shown] = useMount(open);
  if (!render) return null;
  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 60, fontFamily: theme.font }}>
      <div onClick={onClose} style={{ position: 'absolute', inset: 0, background: theme.scrim, opacity: shown ? 1 : 0, transition: 'opacity .26s ease' }}/>
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, maxHeight: maxH,
        background: theme.sc, borderRadius: '28px 28px 0 0', overflow: 'hidden',
        transform: shown ? 'translateY(0)' : 'translateY(101%)',
        transition: 'transform .3s cubic-bezier(.2,.8,.2,1)',
        boxShadow: '0 -8px 40px rgba(0,0,0,0.28)', display: 'flex', flexDirection: 'column',
      }}>
        <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 12, flexShrink: 0 }}>
          <div style={{ width: 34, height: 4, borderRadius: 2, background: theme.outlineVar }}/>
        </div>
        {children}
      </div>
    </div>
  );
}

// ── Waveform ────────────────────────────────────────────────────────────────
function waveAmps(n, seed = 7) {
  const out = [];
  let s = seed;
  for (let i = 0; i < n; i++) {
    s = (s * 9301 + 49297) % 233280;
    const r = s / 233280;
    // layered sines for an organic envelope
    const env = 0.45 + 0.4 * Math.abs(Math.sin(i * 0.21) * Math.cos(i * 0.057));
    out.push(Math.max(0.12, Math.min(1, env * (0.55 + r * 0.7))));
  }
  return out;
}

const fmt = (s) => { s = Math.max(0, Math.round(s)); const m = Math.floor(s / 60), ss = s % 60; return `${m}:${String(ss).padStart(2, '0')}`; };
const fmtDur = (s) => { s = Math.round(s); if (s < 60) return `${s}s`; const m = Math.floor(s / 60), ss = s % 60; return ss ? `${m}m ${ss}s` : `${m}m`; };
const fmtDelta = (s) => (s >= 0 ? '+' : '−') + Math.abs(s).toFixed(1) + 's';

Object.assign(window, {
  makeTheme, ACCENT_HEX, SEG_META, MIcon, Btn, IconBtn, Chip, Sheet, useMount,
  waveAmps, fmt, fmtDur, fmtDelta,
});
