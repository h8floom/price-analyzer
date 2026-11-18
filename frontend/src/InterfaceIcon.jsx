const paths = {
  tag: 'M3 11.5 11.5 3H20v8.5L11.5 20 3 11.5ZM16.5 7.5h.01',
  overview: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  catalog: 'M4 4h16v16H4zM4 9h16M9 9v11',
  favorites: 'M12 20.2 4.2 12.6a4.8 4.8 0 0 1 6.8-6.8l1 1 1-1a4.8 4.8 0 0 1 6.8 6.8Z',
  compare: 'M4 20h16M7 16v-5m5 5V6m5 10V8',
  import: 'M8 3v13m-4-4 4 4 4-4m4 9V8m-4 4 4-4 4 4',
  reports: 'M6 3h8l4 4v14H6zM14 3v5h4M9 12h6m-6 4h6',
  settings: 'M4 7h16M4 17h16M9 4v6m6 4v6',
  search: 'M19 19l-4-4m2-4a6 6 0 1 1-12 0 6 6 0 0 1 12 0Z',
  menu: 'M4 6h16M4 12h16M4 18h16',
  close: 'm6 6 12 12M18 6 6 18',
  plus: 'M12 5v14M5 12h14',
};

export function Brand() {
  return <div className="logo">
    <span className="brand-mark"><InterfaceIcon name="tag" /></span>
    <b translate="no">Price Analyzer</b>
  </div>;
}

export default function InterfaceIcon({ name }) {
  return <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round">
    <path d={paths[name]} />
  </svg>;
}
