import { Badge, Button, Card } from '../common/Ui';
import { money } from '../../utils/formatters';

const categoryIcon = (category = '') => ({ dairy: '◒', bakery: '◐', produce: '◉', beverages: '◌', snacks: '◇' })[category.toLowerCase()] || '✦';

export function ProductCard({ product, inventory, onCart, onWish, wished }) {
  const availableQuantity = Number(inventory?.availableQuantity ?? inventory?.quantity ?? 0);
  const discount = Number(inventory?.discountPercentage ?? product?.discountPercentage ?? 0);
  const price = Number(product.price || 0);
  const finalPrice = discount > 0 ? price * (1 - discount / 100) : price;
  const isOutOfStock = availableQuantity <= 0;
  const isLowStock = availableQuantity > 0 && inventory?.reorderLevel != null && availableQuantity <= inventory.reorderLevel;
  const status = isOutOfStock ? 'OUT OF STOCK' : isLowStock ? 'LOW STOCK' : 'AVAILABLE';
  const statusTone = isOutOfStock ? 'danger' : isLowStock ? 'warning' : 'success';

  return <Card className="product-card"><div className="product-art"><span>{categoryIcon(product.category)}</span>{discount > 0 && <Badge tone="success">{discount}% off</Badge>}{onWish && <button className={`wish-btn ${wished ? 'selected' : ''}`} aria-label="Toggle wishlist" onClick={() => onWish(product.id)}>♡</button>}</div><div className="product-info"><p className="product-brand">{product.brand}</p><h3>{product.name}</h3><p className="product-category">{product.category}</p><div className="price-row"><strong>{money(finalPrice)}</strong>{discount > 0 && <del>{money(price)}</del>}</div><div className="product-meta"><Badge tone={statusTone}>{status}</Badge><span>{availableQuantity > 0 ? `${availableQuantity} available` : 'No units available'}{product.aisle ? ` · Aisle ${product.aisle} · Shelf ${product.shelf || '—'}` : ''}</span></div>{onCart && <Button className="card-cta" onClick={() => onCart(product.id)} disabled={isOutOfStock}>Add to cart</Button>}</div></Card>;
}
