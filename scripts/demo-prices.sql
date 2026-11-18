DO $$
BEGIN
    IF current_database() <> 'price_analyzer' OR current_user <> 'price_user'
        OR inet_server_addr() IS NULL OR inet_server_addr() NOT IN ('127.0.0.1', '::1') THEN
        RAISE EXCEPTION 'Demo seed requires the local price_analyzer database and price_user role';
    END IF;
END $$;

WITH demo(name, category, unit, base_price) AS (
    VALUES ('Демо: кофе молотый, 250 г', 'Напитки', 'упаковка', 365),
           ('Демо: чай чёрный, 100 пакетиков', 'Напитки', 'упаковка', 189),
           ('Демо: шоколад молочный, 90 г', 'Сладости', 'плитка', 119),
           ('Демо: молоко 2,5%, 1 л', 'Молочные продукты', 'литр', 92)
), added AS (
    INSERT INTO products (name, category, unit, description)
    SELECT demo.name, demo.category, demo.unit, 'Учебный пример. Цены вымышлены.'
    FROM demo
    WHERE NOT EXISTS (SELECT 1 FROM products WHERE lower(products.name) = lower(demo.name))
    ON CONFLICT (name) DO NOTHING
    RETURNING id, name
)
INSERT INTO price_records (product_id, store, price, observed_on)
SELECT added.id, shop.name, demo.base_price + shop.offset_price + (week % 2) * 7 - week * 2,
       CURRENT_DATE - (4 - week) * 7
FROM added JOIN demo USING (name)
CROSS JOIN (VALUES ('Магнит', 0), ('Пятёрочка', 9), ('Лента', -5)) AS shop(name, offset_price)
CROSS JOIN generate_series(0, 4) AS weeks(week);
