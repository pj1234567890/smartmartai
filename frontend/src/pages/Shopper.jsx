import { useEffect, useState } from 'react';
import { api } from '../services/api';
import { money, date } from '../utils/formatters';
import { Badge, Button, Card, EmptyState, ErrorState, Skeleton } from '../components/common/Ui';
import { PageHeader } from '../components/layout/Layout';
import { ProductCard } from '../components/products/ProductCard';
import { ChatWindow } from '../components/ai/ChatWindow';

function useLoad(load, deps = []) {
  const [state, setState] = useState({ loading: true, data: null, error: '' });
  const refresh = () => {
    setState({ loading: true, data: null, error: '' });
    load().then((data) => setState({ loading: false, data, error: '' })).catch((e) => setState({ loading: false, data: null, error: e.message }));
  };
  useEffect(refresh, deps);
  return { ...state, refresh };
}

function ProductsView({ toast, go, home = false, onCartChange }) {
  const [query, setQuery] = useState('');
  const [filters, setFilters] = useState({});
  const { data, loading, error, refresh } = useLoad(() => api.products(filters), [filters.name, filters.category]);
  const inventory = useLoad(api.inventory);
  const wishlist = useLoad(api.wishlist);
  const products = data?.content || [];

  const add = async (id) => {
    try {
      await api.addCart(id);
      onCartChange?.();
      toast('Added to your cart');
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const wish = async (id) => {
    try { await api.addWishlist(id); wishlist.refresh(); toast('Saved to your wishlist'); } catch (e) { toast(e.message, 'error'); }
  };

  const apply = (e) => { e?.preventDefault(); setFilters({ name: query }); };
  if (error) return <ErrorState error={error} retry={refresh} />;
  const items = home ? products.slice(0, 4) : products;

  return <>
    {!home && <PageHeader eyebrow="PRODUCT DISCOVERY" title="Explore products" text="Find exactly what you need, with aisle and availability at a glance." />}
    <form className="search-panel" onSubmit={apply}><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="What are you looking for?" aria-label="Search products" /><select value={filters.category || ''} onChange={(e) => setFilters({ ...filters, category: e.target.value })}><option value="">All categories</option>{[...new Set(products.map((p) => p.category))].map((c) => <option key={c}>{c}</option>)}</select><Button type="submit">Search</Button>{(filters.name || filters.category) && <Button variant="ghost" type="button" onClick={() => { setQuery(''); setFilters({}); }}>Clear</Button>}</form>
    {loading || inventory.loading ? <div className="product-grid"><Skeleton count={home ? 4 : 8} className="product-skeleton" /></div> : items.length ? <div className="product-grid">{items.map((product) => <ProductCard key={product.id} product={product} inventory={inventory.data?.find((i) => i.productId === product.id)} wished={wishlist.data?.some((x) => x.productId === product.id || x.id === product.id)} onCart={add} onWish={wish} />)}</div> : <EmptyState title="No products found" text="Try adjusting your search or filters." action={<Button onClick={() => { setQuery(''); setFilters({}); }}>Clear filters</Button>} />}
    {home && products.length > 4 && <div className="section-link"><Button variant="secondary" onClick={() => go('products')}>Explore all products</Button></div>}
  </>;
}

export function ShopperPage({ page, user, toast, go, onCartChange }) {
  if (page === 'products') return <ProductsView toast={toast} go={go} onCartChange={onCartChange} />;
  if (page === 'wishlist') return <Wishlist toast={toast} go={go} onCartChange={onCartChange} />;
  if (page === 'cart') return <Cart toast={toast} go={go} onCartChange={onCartChange} />;
  if (page === 'orders') return <Orders />;
  if (page === 'ai') return <><PageHeader eyebrow="AI SHOPPING ASSISTANT" title="A little help, right when you need it" text="Get real answers about the SmartMart catalogue." /><ChatWindow mode="shopper" /></>;
  return <ShopperHome user={user} toast={toast} go={go} onCartChange={onCartChange} />;
}

function RecommendationStrip({ toast, onCartChange }) {
  const { data, loading, error } = useLoad(api.recommendations);
  const wishlist = useLoad(api.wishlist);
  const add = async (id) => {
    try {
      await api.addCart(id);
      onCartChange?.();
      toast('Added to your cart');
    } catch (e) {
      toast(e.message, 'error');
    }
  };
  const wish = async (id) => {
    try { await api.addWishlist(id); wishlist.refresh(); toast('Saved to your wishlist'); } catch (e) { toast(e.message, 'error'); }
  };
  if (loading) return <section className="section-heading"><div><p className="eyebrow">SMART SUGGESTIONS</p><h2>Recommended for you</h2></div><div className="product-grid"><Skeleton count={4} className="product-skeleton" /></div></section>;
  if (error) return null;
  const products = data || [];
  if (!products.length) return null;

  return <section className="section-heading"><div><p className="eyebrow">SMART SUGGESTIONS</p><h2>Recommended for you</h2></div><div className="product-grid">{products.map((product) => <ProductCard key={product.id} product={product} inventory={{ quantity: product.availableQuantity ?? 0, discountPercentage: product.discountPercentage ?? 0, reorderLevel: 0 }} wished={wishlist.data?.some((x) => x.productId === product.id || x.id === product.id)} onCart={add} onWish={wish} />)}</div></section>;
}

function ShopperHome({ user, toast, go, onCartChange }) {
  const hour = new Date().getHours();
  const greeting = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
  return <>
    <section className="shopper-hero"><div><p className="eyebrow">{greeting.toUpperCase()} · {user.name}</p><h1>Your smarter way to shop.</h1><p>Find what you need, know where it is, and make every trip a little easier.</p><div className="hero-search" onClick={() => go('products')} role="button" tabIndex="0">What are you looking for?<span>Search</span></div></div><div className="hero-orbit"><b>✦</b><span>Simple. Smart. Local.</span></div></section>
    <div className="quick-actions">{[['Explore','Browse products'],['Wishlist','Saved for later'],['Cart','Ready to checkout'],['Ask AI','Find it faster']].map(([a,b], i) => <button onClick={() => go(['products','wishlist','cart','ai'][i])} key={a}><span>0{i + 1}</span><b>{a}</b><small>{b}</small></button>)}</div>
    <section className="section-heading"><div><p className="eyebrow">SHOP THE STORE</p><h2>Explore products</h2></div></section>
    <ProductsView home toast={toast} go={go} onCartChange={onCartChange} />
    <RecommendationStrip toast={toast} onCartChange={onCartChange} />
    <section className="ai-cta"><div><p className="eyebrow">SMARTMART AI</p><h2>Not sure where to start?</h2><p>Ask about availability, aisles, and active offers in natural language.</p><Button variant="light" onClick={() => go('ai')}>Ask the assistant</Button></div><span>✦</span></section>
  </>;
}

function Wishlist({ toast, go, onCartChange }) {
  const { data, loading, error, refresh } = useLoad(api.wishlist);
  const add = async (item) => {
    try {
      await api.addCart(item.productId);
      onCartChange?.();
      toast('Added to your cart');
      refresh();
    } catch (e) { toast(e.message, 'error'); }
  };
  const remove = async (id) => {
    try { await api.removeWishlist(id); toast('Removed from your wishlist'); refresh(); } catch (e) { toast(e.message, 'error'); }
  };

  return <>
    <PageHeader eyebrow="SAVED PRODUCTS" title="My Wishlist" text="Save products here and we’ll keep them handy." />
    {loading ? <Skeleton count={3} className="row-skeleton" /> : error ? <ErrorState error={error} retry={refresh} /> : !data?.length ? <EmptyState title="Your wishlist is waiting." text="Save products here and we’ll keep them handy." action={<Button onClick={() => go('products')}>Explore products</Button>} /> : <div className="list-stack">{data.map((item) => {
      const status = item.availabilityStatus || (item.availableQuantity === 0 ? 'OUT_OF_STOCK' : item.availableQuantity <= 2 ? 'LOW_STOCK' : 'AVAILABLE');
      const statusTone = status === 'OUT_OF_STOCK' ? 'danger' : status === 'LOW_STOCK' ? 'warning' : 'success';
      const discount = Number(item.discountPercentage || 0);
      const isOutOfStock = status === 'OUT_OF_STOCK';
      return <Card className="saved-row" key={item.productId || item.id}><div className="mini-art">✦</div><div><p className="product-brand">{item.brand}</p><h3>{item.name}</h3><p>{money(item.price)}</p>{discount > 0 && <p className="muted">{discount}% active discount</p>}{item.aisle && <p className="muted">Aisle {item.aisle} · Shelf {item.shelf || '—'}</p>}</div><Badge tone={statusTone}>{status === 'AVAILABLE' ? 'AVAILABLE' : status === 'LOW_STOCK' ? 'LOW STOCK' : 'OUT OF STOCK'}</Badge>{isOutOfStock ? <span className="muted">Out of stock</span> : <Button onClick={() => add(item)}>Add to cart</Button>}<Button variant="ghost" onClick={() => remove(item.productId || item.id)}>Remove</Button></Card>; })}</div>}
  </>;
}

function Cart({ toast, go, onCartChange }) {
  const { data, loading, error, refresh } = useLoad(api.cart);
  const [checkingOut, setCheckingOut] = useState(false);
  const modify = async (id, quantity) => {
    try { if (quantity < 1) await api.removeCart(id); else await api.updateCart(id, quantity); refresh(); onCartChange?.(); } catch (e) { toast(e.message, 'error'); }
  };
  const checkout = async () => { setCheckingOut(true); try { await api.checkout(); toast('Checkout successful — your order is on its way'); onCartChange?.(); go('orders'); } catch (e) { toast(e.message, 'error'); } finally { setCheckingOut(false); } };
  if (loading) return <Skeleton count={3} className="row-skeleton" />;
  if (error) return <ErrorState error={error} retry={refresh} />;
  if (!data?.items?.length) return <><PageHeader eyebrow="YOUR SHOPPING BAG" title="Your cart is empty" text="Add a few essentials and they’ll be ready here." /><EmptyState title="Ready when you are" text="Discover products and build your next basket." action={<Button onClick={() => go('products')}>Explore products</Button>} /></>;
  return <><PageHeader eyebrow="YOUR SHOPPING BAG" title="Cart" text={`${data.items.length} item${data.items.length === 1 ? '' : 's'} selected`} /><div className="cart-layout"><div className="list-stack">{data.items.map((item) => <Card className="cart-row" key={item.productId}><div className="mini-art">◌</div><div><h3>{item.name}</h3><p>{money(item.unitPrice)} each · {item.available} available</p><button className="text-btn" onClick={() => modify(item.productId, 0)}>Remove</button></div><div className="quantity"><button onClick={() => modify(item.productId, item.quantity - 1)} aria-label="Decrease quantity">−</button><b>{item.quantity}</b><button onClick={() => modify(item.productId, item.quantity + 1)} disabled={item.quantity >= item.available} aria-label="Increase quantity">+</button></div><strong>{money(item.subtotal)}</strong></Card>)}</div><Card className="summary"><h2>Order summary</h2><div><span>Subtotal</span><b>{money(data.total)}</b></div><div><span>Delivery</span><b>Calculated at checkout</b></div><hr /><div className="summary-total"><span>Total</span><strong>{money(data.total)}</strong></div><Button loading={checkingOut} onClick={checkout}>Checkout securely</Button><small>Stock is confirmed when you place your order.</small></Card></div></>;
}

function Orders() {
  const { data, loading, error, refresh } = useLoad(api.orders);
  return <><PageHeader eyebrow="PURCHASE HISTORY" title="Orders" text="A record of your recent SmartMart orders." />{loading ? <Skeleton count={3} className="row-skeleton" /> : error ? <ErrorState error={error} retry={refresh} /> : !data?.length ? <EmptyState title="No orders yet" text="Your completed orders will appear here." /> : <div className="list-stack">{data.map((order) => <Card className="order-row" key={order.id}><div><p className="eyebrow">ORDER #{order.id}</p><h3>{date(order.createdAt)}</h3><p>{order.items?.length || 0} item{order.items?.length === 1 ? '' : 's'} · {order.items?.map((i) => i.productName).join(', ')}</p></div><Badge tone={order.status === 'COMPLETED' ? 'success' : 'warning'}>{order.status}</Badge><strong>{money(order.totalAmount)}</strong></Card>)}</div>}</>;
}
