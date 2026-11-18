import { useId, useMemo, useRef, useState } from 'react';
import { dateLabel, formatPrice, getSeries } from './lib/priceUtils.js';

const styles = [
  { ink: '#232323', dash: '', hollow: false },
  { ink: '#555651', dash: '9 4', hollow: true },
  { ink: '#73756f', dash: '2 5', hollow: false },
  { ink: '#363733', dash: '12 4 2 4', hollow: true },
  { ink: '#60615c', dash: '5 5', hollow: false },
  { ink: '#444541', dash: '2 4 7 4', hollow: true },
  { ink: '#73756f', dash: '15 4 5 4', hollow: true },
  { ink: '#282923', dash: '2 3 2 7', hollow: false },
];

const tickNumber = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
const emptyHistory = [];
const seriesStyle = (index) => {
  const style = styles[index % styles.length];
  const cycle = Math.floor(index / styles.length);
  return cycle ? { ...style, dash: `${6 + cycle * 3} 4 ${2 + index % styles.length} 4 2 4` } : style;
};

export default function PriceChart({ history = emptyHistory }) {
  const [focusedId, setFocusedId] = useState(null);
  const [hoveredId, setHoveredId] = useState(null);
  const legendHint = useId();
  const graphic = useRef(null);
  const { dates, stores, byStore, positions } = useMemo(() => {
    const { dates, stores } = getSeries(history);
    const byStore = new Map();
    const positions = new Map();
    for (const [index, point] of history.entries()) {
      const key = point.store.toLowerCase();
      if (!byStore.has(key)) byStore.set(key, []);
      byStore.get(key).push(point);
      positions.set(point.id, index);
    }
    for (const points of byStore.values()) points.sort((left, right) => left.date.localeCompare(right.date));
    return { dates, stores, byStore, positions };
  }, [history]);

  if (!history.length) return <p className="empty">Для выбранного периода пока нет цен.</p>;
  const lower = history.reduce((min, point) => Math.min(min, Number(point.price)), Infinity);
  const upper = history.reduce((max, point) => Math.max(max, Number(point.price)), 0);
  const min = Math.floor(lower / 20) * 20;
  const max = Math.ceil(Math.max(upper, min + 20) / 20) * 20;
  const start = Date.parse(dates[0]);
  const end = Date.parse(dates.at(-1));
  const step = Math.ceil(dates.length / 6);
  const coords = (date, price) => [
    82 + (dates.length === 1 ? 293 : (Date.parse(date) - start) * 586 / (end - start)),
    240 - (Number(price) - min) * 209 / (max - min),
  ];
  const lastDate = dates.at(-1);
  const lastX = coords(lastDate, min)[0];
  const labels = [];
  let previousX = -Infinity;
  for (let index = 0; index < dates.length - 1; index += step) {
    const date = dates[index];
    const [x] = coords(date, min);
    if (x - previousX >= 82 && lastX - x >= 82) {
      labels.push(date);
      previousX = x;
    }
  }
  labels.push(lastDate);
  const ticks = [0, 1, 2, 3, 4].map((index) => min + index * (max - min) / 4);
  const active = history[positions.get(hoveredId ?? focusedId)];
  const activePosition = active && coords(active.date, active.price);
  const tabStop = positions.has(focusedId) ? focusedId : history[0].id;

  function movePoint(event) {
    const index = Number(event.target.dataset.chartPoint);
    if (!Number.isInteger(index)) return;
    const next = ({
      ArrowLeft: Math.max(0, index - 1),
      ArrowRight: Math.min(history.length - 1, index + 1),
      Home: 0,
      End: history.length - 1,
    })[event.key];
    if (next === undefined) return;
    event.preventDefault();
    setHoveredId(null);
    graphic.current.querySelector(`[data-chart-point="${next}"]`)?.focus();
  }

  return <div className="chart-wrap">
    <div className="chart-plot">
      <svg ref={graphic} viewBox="0 0 720 280" role="group" aria-label="Линейный график динамики цен"
        aria-describedby={legendHint} onKeyDown={movePoint} onPointerLeave={() => setHoveredId(null)}>
        {ticks.map((value, index) => {
          const [, y] = coords(dates[0], value);
          return <g key={value}>
            <line className={index === 0 ? 'chart-baseline' : 'chart-grid'} x1="82" x2="668" y1={y} y2={y} />
            <text x="68" y={y + 4} textAnchor="end" className="axis">{tickNumber.format(value)} ₽</text>
          </g>;
        })}
        {labels.map((date, index) => <text key={date} x={coords(date, min)[0]} y="264" textAnchor="middle"
          className={index === 0 || index === labels.length - 1 ? 'axis' : 'axis axis-intermediate'}>{dateLabel(date)}</text>)}
        {active && <line className="chart-cursor" x1={coords(active.date, min)[0]} x2={coords(active.date, min)[0]}
          y1="31" y2="240" aria-hidden="true" />}
        {stores.map((store, index) => {
          const points = byStore.get(store.toLowerCase());
          const style = seriesStyle(index);
          const line = points.map((point) => coords(point.date, point.price).map((value) => value.toFixed(2)).join(',')).join(' ');
          return <g key={store}>
            {points.length > 1 && <polyline points={line} stroke={style.ink} fill="none" strokeWidth="2"
              strokeDasharray={style.dash || undefined} strokeLinejoin="round" vectorEffect="non-scaling-stroke" />}
            {points.map((point) => {
              const [x, y] = coords(point.date, point.price);
              const label = `${store}: ${formatPrice(point.price)} — ${point.date}`;
              return <circle key={point.id} cx={x} cy={y} r={active?.id === point.id ? 7 : 4.5}
                fill={style.hollow ? 'white' : style.ink} stroke={style.hollow ? style.ink : 'white'}
                strokeWidth="1.4" vectorEffect="non-scaling-stroke" tabIndex={tabStop === point.id ? 0 : -1}
                data-chart-point={positions.get(point.id)} aria-label={label} onFocus={() => setFocusedId(point.id)}
                onPointerEnter={() => setHoveredId(point.id)} onClick={(event) => event.currentTarget.focus()}>
                <title>{label}</title>
              </circle>;
            })}
          </g>;
        })}
      </svg>
      {active && <div className="chart-popup" aria-hidden="true"
        style={{ '--plot-x': `${activePosition[0] / 7.2}%`, '--plot-y': `${activePosition[1] / 2.8}%` }}>
        <strong>{formatPrice(active.price)}</strong><span>{active.store} / {dateLabel(active.date)}</span>
      </div>}
    </div>
    <ul className="legend" aria-label="Линии магазинов">{stores.map((store, index) => {
      const style = seriesStyle(index);
      return <li key={store}>
        <svg width="30" height="12" viewBox="0 0 30 12" aria-hidden="true">
          <line x1="1" x2="29" y1="6" y2="6" stroke={style.ink} strokeWidth="2"
            strokeDasharray={style.dash || undefined} />
          <circle cx="15" cy="6" r="3" fill={style.hollow ? 'white' : style.ink} stroke={style.ink} />
        </svg>{store}
      </li>;
    })}</ul>
    <div className="chart-detail">
      <p id={legendHint} className="chart-instructions">Выберите точку. Клавиши ← и → переходят между ценами.</p>
      <p className="chart-probe sr-only" aria-live="polite">
        {active && `${active.store}: ${formatPrice(active.price)} — ${active.date}`}
      </p>
    </div>
  </div>;
}
