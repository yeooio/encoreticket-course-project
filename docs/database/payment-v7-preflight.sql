-- Read-only preflight on the target V6 database, under a maintenance write freeze.
-- Every query must return zero rows. Investigate conflicts; never delete financial records automatically.
SELECT order_id, id AS payment_id, payment_no
FROM payment
WHERE status = 1 AND order_id IN (
    SELECT order_id FROM payment WHERE status = 1 GROUP BY order_id HAVING COUNT(*) > 1
)
ORDER BY order_id, id;

SELECT p.id AS payment_id, p.order_id, p.amount, o.total_amount
FROM payment p LEFT JOIN orders o ON o.id = p.order_id
WHERE o.id IS NULL OR p.amount <> o.total_amount;
