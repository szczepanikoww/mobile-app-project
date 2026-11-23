-- Positions for the map on the gyms screen. Only fills gyms that have none, matched by the synthetic demo names,
-- so a database seeded before the map existed gets pins too; it changes nothing elsewhere.
UPDATE catalogue.gym AS g SET latitude = v.lat, longitude = v.lng
FROM (VALUES
    ('Studio Centrum', 52.229700, 21.012200),
    ('Forma Wola', 52.237000, 20.965000),
    ('Ruch Mokotów', 52.193000, 21.025000),
    ('Hala Praga', 52.240000, 21.080000),
    ('Puls Żoliborz', 52.270000, 20.985000),
    ('Strefa Ursynów', 52.140000, 21.045000)
) AS v(name, lat, lng)
WHERE g.name = v.name AND g.latitude IS NULL AND g.longitude IS NULL;
