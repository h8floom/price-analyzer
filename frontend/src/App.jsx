import { formatPrice } from './lib/formatPrice.js';
import './App.css';

const exampleProducts = [
  { id: 1, name: 'Кофе молотый', store: 'Магазин А', price: 349.9 },
  { id: 2, name: 'Чай чёрный', store: 'Магазин Б', price: 219.0 },
];

export default function App() {
  return (
    <main className="page">
      <header>
        <p className="eyebrow">Учебный проект</p>
        <h1>Анализ цен на товары</h1>
        <p>Сбор, хранение и сравнение цен в разных магазинах.</p>
      </header>
      <section aria-label="Пример каталога">
        <h2>Пример каталога</h2>
        <table>
          <thead>
            <tr><th>Товар</th><th>Магазин</th><th>Цена</th></tr>
          </thead>
          <tbody>
            {exampleProducts.map((product) => (
              <tr key={product.id}>
                <td>{product.name}</td><td>{product.store}</td><td>{formatPrice(product.price)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </main>
  );
}
