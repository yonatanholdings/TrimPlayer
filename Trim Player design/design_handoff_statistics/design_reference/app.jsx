// app.jsx — Final cut: 5 polished editorial-system screens (Subscriptions
// adopts the Calm screen's data-viz inside the same palette).

const SCREENS = [
  { id: 'overview',  title: 'Overview',     sub: 'Front page of the issue',                Comp: 'Overview' },
  { id: 'subs',      title: 'Subscriptions', sub: 'Where the hours went · Calm structure, editorial paint', Comp: 'Subscriptions' },
  { id: 'activity',  title: 'Activity',     sub: 'Habits + finished/abandoned + saved',    Comp: 'Activity' },
  { id: 'years',     title: 'Years',        sub: 'Six-year streamgraph',                   Comp: 'Years' },
  { id: 'feed',      title: 'Per-feed',     sub: 'Dossier modal',                          Comp: 'FeedDialog' },
];

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "showFrame": true
}/*EDITMODE-END*/;

function Frame({ children, showFrame }) {
  if (!showFrame) {
    return (
      <div style={{ width: 400, height: 860, background: '#f4ede0', overflow: 'hidden', borderRadius: 4, boxShadow: '0 30px 60px rgba(0,0,0,0.16)' }}>
        <div style={{ width: '100%', height: '100%', overflow: 'auto' }}>{children}</div>
      </div>
    );
  }
  return (
    <AndroidDevice width={400} height={860}>
      {children}
    </AndroidDevice>
  );
}

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  return (
    <div style={{ width: '100%', height: '100%' }}>
      <DesignCanvas
        title="Trim Player · Statistics, the editorial cut"
        subtitle="Five screens. Editorial system throughout; Subscriptions keeps Calm’s donut + per-show bars, repainted in cream and serif."
      >
        <DCSection id="screens" title="The issue" subtitle="Click any artboard’s ⤢ icon to focus.">
          {SCREENS.map(s => {
            const Comp = window[s.Comp];
            return (
              <DCArtboard key={s.id} id={s.id} label={s.title} width={400} height={860}>
                <Frame showFrame={t.showFrame}><Comp/></Frame>
              </DCArtboard>
            );
          })}
        </DCSection>
      </DesignCanvas>

      <TweaksPanel title="Tweaks">
        <TweakSection label="Presentation">
          <TweakToggle label="Show device frame" value={t.showFrame} onChange={v => setTweak('showFrame', v)}/>
        </TweakSection>
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
