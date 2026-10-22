import { useEffect, useState } from 'react';
import { api } from '../services/api';
import { date, daysUntil, money, moneyExact } from '../utils/formatters';
import { Badge, Button, Card, EmptyState, ErrorState, Modal, Skeleton } from '../components/common/Ui';
import { PageHeader } from '../components/layout/Layout';
import { ChatWindow } from '../components/ai/ChatWindow';
import { ReceivingPage } from '../components/receiving/ReceivingPage';

function useLoad(load, deps = []) {
  const [state, setState] = useState({ loading: true, data: null, error: '' });
  const refresh = () => {
    setState({ loading: true, data: null, error: '' });
    load().then((data) => setState({ loading: false, data, error: '' })).catch((e) => setState({ loading: false, data: null, error: e.message }));
  };
  useEffect(refresh, deps);
  return { ...state, refresh };
}

const tone = (q, reorder, expiry) => {
  const days = daysUntil(expiry);
  if (days !== null && days < 0) return ['danger', 'Expired'];
  if (q <= reorder) return ['danger', 'Critical'];
  if (days !== null && days <= 7) return ['warning', 'Expiring'];
  return ['success', 'Healthy'];
};

const riskTone = (risk) => ({ OUT_OF_STOCK: 'danger', HIGH: 'danger', MEDIUM: 'warning', LOW: 'success' }[risk] || 'neutral');
const expiryTone = (status) => ({ REMOVE_FROM_SALE: 'danger', PRIORITIZE_SALE: 'warning', DISCOUNT: 'success', WARN: 'warning', MONITOR: 'success' }[status] || 'neutral');

export function ManagerPage({ page, toast }) {
  if (page === 'dashboard') return <Dashboard toast={toast} />;
  if (page === 'products') return <ManagerProducts toast={toast} />;
  if (page === 'inventory') return <Inventory title="Inventory" text="Monitor stock levels, expiry dates, and product readiness." load={api.inventory} />;
  if (page === 'inventory-management') return <InventoryManagement toast={toast} />;
  if (page === 'low-stock') return <Inventory title="Low Stock" text="Products currently at or below their reorder level." load={api.lowStock} attention />;
  if (page === 'expiring') return <Inventory title="Expiring inventory" text="Prioritize items approaching their expiry date." load={api.expiring} expiry />;
  if (page === 'discounts') return <Discounts toast={toast} />;
  if (page === 'orders') return <ManagerOrders toast={toast} />;
  if (page === 'receiving') return <ReceivingPage toast={toast} />;
  return <><PageHeader eyebrow="AI OPERATIONS ASSISTANT" title="A clearer view of your store" text="Ask SmartMart AI about inventory, orders, and daily priorities." /><ChatWindow mode="manager" /></>;
}

function Dashboard({ toast }) {
  const analytics = useLoad(api.analytics);
  const stockRisks = useLoad(api.stockRisks);
  const reorder = useLoad(api.reorderRecommendations);
  const expiry = useLoad(api.expiryInsights);
  const refreshAll = () => { analytics.refresh(); stockRisks.refresh(); reorder.refresh(); expiry.refresh(); };

  const applyDiscount = async (productId, percentage) => {
    try {
      await api.applyDiscount(productId, Number(percentage));
      toast('Discount applied successfully');
      refreshAll();
    } catch (err) {
      toast(err.message, 'error');
    }
  };

  if (analytics.loading) return <><PageHeader eyebrow="STORE OVERVIEW" title="Good to see you" text="Here’s what needs your attention today." /><div className="stats-grid"><Skeleton count={6} className="stat-skeleton" /></div></>;
  if (analytics.error) return <ErrorState error={analytics.error} retry={analytics.refresh} />;

  const data = analytics.data || {};
  const stats = [['Total products', data.totalProducts, 'Catalogue'], ['Inventory units', data.totalInventoryUnits, 'On hand'], ['Low stock', data.lowStockProducts, 'Needs attention'], ['Expiring soon', data.expiringInventory, 'Within 30 days'], ['Total orders', data.totalOrders, 'All time'], ['Revenue', money(data.revenue), 'All time']];
  const ranked = (map) => Object.entries(map || {}).sort((a, b) => b[1] - a[1]).slice(0, 6);
  const max = Math.max(...ranked(data.topCategories).map(([, v]) => v), 1);

  return <>
    <PageHeader eyebrow="STORE OVERVIEW" title="Store intelligence, at a glance" text="Live figures calculated from your SmartMart data." />
    <div className="stats-grid">{stats.map(([label, value, caption], i) => <Card className={`stat-card stat-${i}`} key={label}><span>{caption}</span><strong>{value}</strong><p>{label}</p></Card>)}</div>
    <div className="dashboard-grid">
      <Card className="insight-card"><div className="section-heading"><div><p className="eyebrow">INVENTORY INTELLIGENCE</p><h2>Priority signals</h2></div></div><div className="priority-list"><div><span className="signal danger" /><p><b>{data.lowStockProducts} products</b><small>need a stock review</small></p></div><div><span className="signal warning" /><p><b>{data.expiringInventory} items</b><small>expire within 30 days</small></p></div><div><span className="signal success" /><p><b>{data.totalInventoryUnits} units</b><small>currently tracked</small></p></div></div></Card>
      <Card className="chart-card"><p className="eyebrow">TOP CATEGORIES</p><h2>Order volume by category</h2>{ranked(data.topCategories).length ? <div className="bar-chart">{ranked(data.topCategories).map(([name, value]) => <div key={name}><span>{name}</span><i><b style={{ width: `${value / max * 100}%` }} /></i><strong>{value}</strong></div>)}</div> : <p className="muted">Category order data will appear as orders are placed.</p>}</Card>
    </div>

    <div className="dashboard-grid">
      <Card className="insight-card"><div className="section-heading"><div><p className="eyebrow">STOCK-OUT RISKS</p><h2>Critical product risks</h2></div></div>{stockRisks.loading ? <Skeleton count={3} className="row-skeleton" /> : stockRisks.error ? <ErrorState error={stockRisks.error} retry={stockRisks.refresh} /> : !stockRisks.data?.length ? <p className="muted">No stock-out risks at the moment.</p> : <div className="table-wrap"><table><thead><tr><th>Product</th><th>Stock</th><th>Avg sales</th><th>Days left</th><th>Risk</th><th>Reorder</th><th>Wishlist</th><th>Reason</th></tr></thead><tbody>{stockRisks.data.map((item) => <tr key={item.productId}><td><b>{item.productName}</b></td><td>{item.currentStock}</td><td>{Number(item.averageDailySales || 0).toFixed(2)}</td><td>{item.estimatedDaysRemaining == null ? '—' : Number(item.estimatedDaysRemaining).toFixed(0)}</td><td><Badge tone={riskTone(item.riskLevel)}>{item.riskLevel?.replace('_', ' ')}</Badge></td><td>{item.suggestedReorderQuantity}</td><td>{item.wishlistDemand}</td><td>{item.reason}</td></tr>)}</tbody></table></div>}</Card>

      <Card className="insight-card"><div className="section-heading"><div><p className="eyebrow">REORDER</p><h2>Suggested replenishment</h2></div></div>{reorder.loading ? <Skeleton count={3} className="row-skeleton" /> : reorder.error ? <ErrorState error={reorder.error} retry={reorder.refresh} /> : !reorder.data?.length ? <p className="muted">No reorder actions right now.</p> : <div className="table-wrap"><table><thead><tr><th>Product</th><th>Stock</th><th>Qty</th><th>Reason</th></tr></thead><tbody>{reorder.data.map((item) => <tr key={item.productId}><td><b>{item.productName}</b></td><td>{item.currentStock}</td><td>{item.suggestedReorderQuantity}</td><td>{item.reason}</td></tr>)}</tbody></table></div>}</Card>
    </div>

    <Card className="popular-card"><div className="section-heading"><div><p className="eyebrow">EXPIRY INTELLIGENCE</p><h2>Expiry actions</h2></div></div>{expiry.loading ? <Skeleton count={3} className="row-skeleton" /> : expiry.error ? <ErrorState error={expiry.error} retry={expiry.refresh} /> : !expiry.data?.length ? <p className="muted">No expiry alerts.</p> : <div className="table-wrap"><table><thead><tr><th>Product</th><th>Qty</th><th>Days</th><th>Status</th><th>Action</th><th>Discount</th><th /></tr></thead><tbody>{expiry.data.map((item) => <tr key={item.productId}><td><b>{item.productName}</b></td><td>{item.quantity}</td><td>{item.daysUntilExpiry}</td><td><Badge tone={expiryTone(item.status)}>{item.status}</Badge></td><td>{item.recommendedAction}</td><td>{item.recommendedDiscount && Number(item.recommendedDiscount) > 0 ? `${Number(item.recommendedDiscount).toFixed(0)}%` : '—'}</td><td>{item.recommendedDiscount && Number(item.recommendedDiscount) > 0 ? <Button variant="secondary" onClick={() => applyDiscount(item.productId, item.recommendedDiscount)}>Apply discount</Button> : null}</td></tr>)}</tbody></table></div>}</Card>

    <Card className="popular-card"><div className="section-heading"><div><p className="eyebrow">CUSTOMER DEMAND</p><h2>Popular products</h2></div></div>{ranked(data.popularProducts).length ? <div className="table-wrap"><table><thead><tr><th>Product</th><th>Units ordered</th><th>Signal</th></tr></thead><tbody>{ranked(data.popularProducts).map(([name, units]) => <tr key={name}><td><b>{name}</b></td><td>{units}</td><td><Badge tone="success">Popular</Badge></td></tr>)}</tbody></table></div> : <EmptyState title="No order patterns yet" text="Popular products will surface once orders are placed." />}</Card>
  </>;
}

function Inventory({ title, text, load, attention, expiry }) {
  const { data, loading, error, refresh } = useLoad(load);
  const [query, setQuery] = useState('');
  const rows = (data || []).filter((x) => x.productName.toLowerCase().includes(query.toLowerCase()));
  return <><PageHeader eyebrow="INVENTORY CONTROL" title={title} text={text} action={<Button variant="secondary" onClick={refresh}>Refresh</Button>} /><div className="table-toolbar"><input placeholder="Search products" value={query} onChange={(e) => setQuery(e.target.value)} /></div>{loading ? <Skeleton count={6} className="row-skeleton" /> : error ? <ErrorState error={error} retry={refresh} /> : !rows.length ? <EmptyState title={attention ? 'Everything looks healthy.' : expiry ? 'No products are approaching expiry.' : 'No inventory found'} text={attention ? 'Nothing is currently below its reorder level.' : 'There is nothing to review right now.'} /> : <Card className="table-card"><div className="table-wrap"><table><thead><tr><th>Product</th><th>Current stock</th><th>Reorder level</th><th>Expiry</th><th>Discount</th><th>Status</th></tr></thead><tbody>{rows.map((item) => { const [statusTone, status] = tone(item.quantity, item.reorderLevel, item.expiryDate); const days = daysUntil(item.expiryDate); return <tr key={item.id}><td><b>{item.productName}</b><small>Product #{item.productId}</small></td><td>{item.quantity}</td><td>{item.reorderLevel}</td><td>{date(item.expiryDate)}{expiry && <small>{days < 0 ? 'Expired' : `${days} days remaining`}</small>}</td><td>{item.discountPercentage ? `${item.discountPercentage}%` : '—'}</td><td><Badge tone={statusTone}>{status}</Badge></td></tr>; })}</tbody></table></div></Card>}</>;
}

function Discounts({ toast }) {
  const { data, loading, error, refresh } = useLoad(api.discounts);
  const [selected, setSelected] = useState(null);
  const [percentage, setPercentage] = useState('');
  const [saving, setSaving] = useState(false);
  const save = async (e) => { e.preventDefault(); setSaving(true); try { await api.applyDiscount(selected.productId, Number(percentage)); toast('Discount applied successfully'); setSelected(null); refresh(); } catch (err) { toast(err.message, 'error'); } finally { setSaving(false); } };
  return <><PageHeader eyebrow="PRICING CONTROL" title="Active discounts" text="Review current markdowns and apply deliberate changes." />{loading ? <Skeleton count={4} className="row-skeleton" /> : error ? <ErrorState error={error} retry={refresh} /> : !data?.length ? <EmptyState title="No active discounts" text="Discounted inventory will appear here." /> : <Card className="table-card"><div className="table-wrap"><table><thead><tr><th>Product</th><th>Discount</th><th>Inventory</th><th>Expiry</th><th /></tr></thead><tbody>{data.map((item) => <tr key={item.id}><td><b>{item.productName}</b></td><td><Badge tone="success">{item.discountPercentage}% off</Badge></td><td>{item.quantity} units</td><td>{date(item.expiryDate)}</td><td><Button variant="secondary" onClick={() => { setSelected(item); setPercentage(item.discountPercentage); }}>Edit discount</Button></td></tr>)}</tbody></table></div></Card>}{selected && <Modal title={`Discount · ${selected.productName}`} onClose={() => setSelected(null)}><form className="modal-form" onSubmit={save}><label>Discount percentage<input type="number" min="0" max="100" step="0.01" value={percentage} onChange={(e) => setPercentage(e.target.value)} required /></label><p>This change applies to the selected product’s inventory record.</p><div><Button variant="ghost" type="button" onClick={() => setSelected(null)}>Cancel</Button><Button loading={saving}>Save discount</Button></div></form></Modal>}</>;
}

function InventoryManagement({ toast }) {
  const { data: products, loading: pLoading, error: pError, refresh: pRefresh } = useLoad(api.products);
  const { data: inventory, loading: iLoading, error: iError, refresh: iRefresh } = useLoad(api.inventory);
  const [selected, setSelected] = useState(null);
  const [action, setAction] = useState(null);
  const [formData, setFormData] = useState({});
  const [saving, setSaving] = useState(false);
  const [query, setQuery] = useState('');

  const productMap = Object.fromEntries((inventory || []).map(inv => [inv.productId, inv]));
  const allProducts = (products?.content || []).filter(p => p.name.toLowerCase().includes(query.toLowerCase()));
  const productsWithoutInventory = allProducts.filter(p => !productMap[p.id]);
  const productsWithInventory = allProducts.filter(p => productMap[p.id]).map(p => ({ ...p, inv: productMap[p.id] }));

  const openForm = (product, actionType) => {
    setSelected(product);
    setAction(actionType);
    if (actionType === 'add') {
      setFormData({ productId: product.id, quantity: 1, reorderLevel: 5, expiryDate: '', discountPercentage: 0, aisle: '', shelf: '' });
    } else if (actionType === 'restock') {
      setFormData({ quantity: 1 });
    } else if (actionType === 'adjust') {
      setFormData({ quantity: 0, reason: '' });
    } else if (actionType === 'edit') {
      setFormData({ quantity: product.inv.quantity, reorderLevel: product.inv.reorderLevel, expiryDate: product.inv.expiryDate || '', discountPercentage: product.inv.discountPercentage || 0, aisle: product.inv.aisle || '', shelf: product.inv.shelf || '' });
    }
  };

  const handleSave = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      if (action === 'add') {
        await api.createInventory(formData);
        toast('Inventory created successfully');
      } else if (action === 'restock') {
        await api.restockInventory(selected.inv.id, formData.quantity);
        toast('Stock replenished successfully');
      } else if (action === 'adjust') {
        await api.adjustStock(selected.inv.id, formData.quantity, formData.reason);
        toast('Stock adjusted successfully');
      } else if (action === 'edit') {
        await api.updateInventory(selected.inv.id, { productId: selected.id, quantity: formData.quantity, reorderLevel: formData.reorderLevel, expiryDate: formData.expiryDate, discountPercentage: formData.discountPercentage, aisle: formData.aisle, shelf: formData.shelf });
        toast('Inventory updated successfully');
      }
      setAction(null);
      setSelected(null);
      iRefresh();
      pRefresh();
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      setSaving(false);
    }
  };

  const statusBadge = (qty, reorder, expiry) => {
    const days = daysUntil(expiry);
    if (days !== null && days < 0) return ['danger', 'Expired'];
    if (qty <= 0) return ['danger', 'Out of stock'];
    if (qty <= reorder) return ['danger', 'Low stock'];
    if (days !== null && days <= 7) return ['warning', 'Expiring'];
    return ['success', 'Healthy'];
  };

  if (pLoading || iLoading) return <><PageHeader eyebrow="INVENTORY CONTROL" title="Inventory Management" text="Add, manage, and track inventory for all products." /><Skeleton count={6} className="row-skeleton" /></>;
  if (pError || iError) return <><PageHeader eyebrow="INVENTORY CONTROL" title="Inventory Management" text="Add, manage, and track inventory for all products." /><ErrorState error={pError || iError} retry={pError ? pRefresh : iRefresh} /></>;

  return <>
    <PageHeader eyebrow="INVENTORY CONTROL" title="Inventory Management" text="Add, manage, and track inventory for all products." action={<Button variant="secondary" onClick={() => { pRefresh(); iRefresh(); }}>Refresh</Button>} />
    <div className="table-toolbar"><input placeholder="Search products" value={query} onChange={(e) => setQuery(e.target.value)} /></div>

    {productsWithoutInventory.length > 0 && <Card><div className="section-heading"><p className="eyebrow">NO INVENTORY</p><h2>Products awaiting stock setup</h2></div><div className="table-wrap"><table><thead><tr><th>Product</th><th>Brand</th><th>Category</th><th>Price</th><th /></tr></thead><tbody>{productsWithoutInventory.map((p) => <tr key={p.id}><td><b>{p.name}</b></td><td>{p.brand}</td><td>{p.category}</td><td>{money(p.price)}</td><td><Button variant="secondary" onClick={() => openForm(p, 'add')}>Add Inventory</Button></td></tr>)}</tbody></table></div></Card>}

    {productsWithInventory.length > 0 && <Card className="table-card"><div className="section-heading"><p className="eyebrow">ACTIVE INVENTORY</p><h2>Managed products</h2></div><div className="table-wrap"><table><thead><tr><th>Product</th><th>Stock</th><th>Reorder</th><th>Expiry</th><th>Aisle</th><th>Shelf</th><th>Discount</th><th>Status</th><th></th></tr></thead><tbody>{productsWithInventory.map((p) => { const [tone, status] = statusBadge(p.inv.quantity, p.inv.reorderLevel, p.inv.expiryDate); return <tr key={p.id}><td><b>{p.name}</b></td><td>{p.inv.quantity}</td><td>{p.inv.reorderLevel}</td><td>{date(p.inv.expiryDate)}</td><td>{p.inv.aisle || '—'}</td><td>{p.inv.shelf || '—'}</td><td>{p.inv.discountPercentage ? `${p.inv.discountPercentage}%` : '—'}</td><td><Badge tone={tone}>{status}</Badge></td><td><div style={{display:'flex',gap:'0.5rem'}}><Button variant="secondary" size="small" onClick={() => openForm(p, 'restock')}>Restock</Button><Button variant="secondary" size="small" onClick={() => openForm(p, 'adjust')}>Adjust</Button><Button variant="secondary" size="small" onClick={() => openForm(p, 'edit')}>Edit</Button></div></td></tr>; })}</tbody></table></div></Card>}

    {action && <Modal title={action === 'add' ? 'Add Inventory' : action === 'restock' ? 'Restock' : action === 'adjust' ? 'Adjust Stock' : 'Edit Inventory'} onClose={() => { setAction(null); setSelected(null); }}><form className="modal-form" onSubmit={handleSave}>
      {action === 'add' && <>
        <label>Quantity<input type="number" min="1" value={formData.quantity || ''} onChange={(e) => setFormData({...formData, quantity: parseInt(e.target.value) || 0})} required /></label>
        <label>Reorder Level<input type="number" min="0" value={formData.reorderLevel || ''} onChange={(e) => setFormData({...formData, reorderLevel: parseInt(e.target.value) || 0})} required /></label>
        <label>Expiry Date<input type="date" value={formData.expiryDate || ''} onChange={(e) => setFormData({...formData, expiryDate: e.target.value})} /></label>
        <label>Discount %<input type="number" min="0" max="100" step="0.01" value={formData.discountPercentage || ''} onChange={(e) => setFormData({...formData, discountPercentage: parseFloat(e.target.value) || 0})} /></label>
        <label>Aisle<input type="text" maxLength="50" value={formData.aisle || ''} onChange={(e) => setFormData({...formData, aisle: e.target.value})} /></label>
        <label>Shelf<input type="text" maxLength="50" value={formData.shelf || ''} onChange={(e) => setFormData({...formData, shelf: e.target.value})} /></label>
      </>}
      {action === 'restock' && <>
        <label>Quantity to Add<input type="number" min="1" value={formData.quantity || ''} onChange={(e) => setFormData({...formData, quantity: parseInt(e.target.value) || 0})} required /></label>
      </>}
      {action === 'adjust' && <>
        <label>Quantity Adjustment<input type="number" value={formData.quantity || ''} onChange={(e) => setFormData({...formData, quantity: parseInt(e.target.value) || 0})} required /></label>
        <label>Reason<input type="text" value={formData.reason || ''} onChange={(e) => setFormData({...formData, reason: e.target.value})} /></label>
      </>}
      {action === 'edit' && <>
        <label>Quantity<input type="number" min="0" value={formData.quantity || ''} onChange={(e) => setFormData({...formData, quantity: parseInt(e.target.value) || 0})} required /></label>
        <label>Reorder Level<input type="number" min="0" value={formData.reorderLevel || ''} onChange={(e) => setFormData({...formData, reorderLevel: parseInt(e.target.value) || 0})} required /></label>
        <label>Expiry Date<input type="date" value={formData.expiryDate || ''} onChange={(e) => setFormData({...formData, expiryDate: e.target.value})} /></label>
        <label>Discount %<input type="number" min="0" max="100" step="0.01" value={formData.discountPercentage || ''} onChange={(e) => setFormData({...formData, discountPercentage: parseFloat(e.target.value) || 0})} /></label>
        <label>Aisle<input type="text" maxLength="50" value={formData.aisle || ''} onChange={(e) => setFormData({...formData, aisle: e.target.value})} /></label>
        <label>Shelf<input type="text" maxLength="50" value={formData.shelf || ''} onChange={(e) => setFormData({...formData, shelf: e.target.value})} /></label>
      </>}
      <div><Button variant="ghost" type="button" onClick={() => { setAction(null); setSelected(null); }}>Cancel</Button><Button loading={saving}>Save</Button></div>
    </form></Modal>}
  </>;
}

const blankProduct = { name: '', brand: '', category: '', price: '', aisle: '', shelf: '', description: '' };

function ManagerProducts({ toast }) {
  const { data, loading, error, refresh } = useLoad(api.products);
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState(null);
  const [removing, setRemoving] = useState(null);
  const products = (data?.content || []).filter((x) => `${x.name} ${x.brand}`.toLowerCase().includes(query.toLowerCase()));
  const remove = async () => {
    try { await api.deleteProduct(removing.id); toast('Product deleted'); setRemoving(null); refresh(); } catch (e) { toast(e.message, 'error'); }
  };
  return <><PageHeader eyebrow="CATALOGUE MANAGEMENT" title="Products" text="Keep your catalogue accurate and easy to browse." action={<Button onClick={() => setEditing(blankProduct)}>Add product</Button>} /><div className="table-toolbar"><input placeholder="Search catalogue" value={query} onChange={(e) => setQuery(e.target.value)} /></div>{loading ? <Skeleton count={6} className="row-skeleton" /> : error ? <ErrorState error={error} retry={refresh} /> : !products.length ? <EmptyState title="No products found" text="Add a product or adjust your search." /> : <Card className="table-card"><div className="table-wrap"><table><thead><tr><th>Product</th><th>Brand</th><th>Category</th><th>Price</th><th>Location</th><th /></tr></thead><tbody>{products.map((p) => <tr key={p.id}><td><b>{p.name}</b><small>{p.description || 'No description'}</small></td><td>{p.brand}</td><td>{p.category}</td><td>{money(p.price)}</td><td>Aisle {p.aisle || '—'} · Shelf {p.shelf || '—'}</td><td><Button variant="ghost" onClick={() => setEditing(p)}>Edit</Button><button className="danger-link" onClick={() => setRemoving(p)}>Delete</button></td></tr>)}</tbody></table></div></Card>}{editing && <ProductForm product={editing} close={() => setEditing(null)} refresh={refresh} toast={toast} />}{removing && <Modal title="Delete product?" onClose={() => setRemoving(null)}><p className="confirm-copy">This will permanently remove <b>{removing.name}</b> from the catalogue.</p><div className="modal-actions"><Button variant="ghost" onClick={() => setRemoving(null)}>Cancel</Button><Button variant="danger" onClick={remove}>Delete product</Button></div></Modal>}</>;
}

function ProductForm({ product, close, refresh, toast }) {
  const [form, setForm] = useState(product);
  const [saving, setSaving] = useState(false);
  const update = (e) => setForm({ ...form, [e.target.name]: e.target.value });
  const save = async (e) => { e.preventDefault(); setSaving(true); try { const body = { ...form, price: Number(form.price) }; if (product.id) await api.updateProduct(product.id, body); else await api.createProduct(body); toast(product.id ? 'Product updated' : 'Product created'); close(); refresh(); } catch (err) { toast(err.message, 'error'); } finally { setSaving(false); } };
  return <Modal title={product.id ? 'Edit product' : 'Add product'} onClose={close}><form className="modal-form product-form" onSubmit={save}><label>Name<input name="name" value={form.name} onChange={update} required /></label><label>Brand<input name="brand" value={form.brand} onChange={update} required /></label><label>Category<input name="category" value={form.category} onChange={update} required /></label><label>Price<input name="price" type="number" min="0.01" step="0.01" value={form.price} onChange={update} required /></label><label>Aisle<input name="aisle" value={form.aisle || ''} onChange={update} /></label><label>Shelf<input name="shelf" value={form.shelf || ''} onChange={update} /></label><label className="wide">Description<textarea name="description" value={form.description || ''} onChange={update} rows="3" /></label><div className="modal-actions wide"><Button type="button" variant="ghost" onClick={close}>Cancel</Button><Button loading={saving}>{product.id ? 'Save changes' : 'Create product'}</Button></div></form></Modal>;
}

function ManagerOrders({ toast }) {
  const { data, loading, error, refresh } = useLoad(api.orders);
  const stages = ['PLACED', 'PREPARING', 'READY_FOR_DELIVERY', 'OUT_FOR_DELIVERY', 'DELIVERED'];
  const updateStatus = async (id, status) => { try { await api.updateDeliveryStatus(id, status); toast('Delivery status updated'); refresh(); } catch (e) { toast(e.message, 'error'); refresh(); } };
  return <><PageHeader eyebrow="ORDER OPERATIONS" title="Orders" text="Review order contents and manage store delivery fulfilment." action={<Button variant="secondary" onClick={refresh}>Refresh orders</Button>} />{loading ? <Skeleton count={5} className="row-skeleton" /> : error ? <ErrorState error={error} retry={refresh} /> : !data?.length ? <EmptyState title="No orders yet" text="Customer orders will appear here as they are placed." /> : <Card className="table-card"><div className="table-wrap"><table><thead><tr><th>Order / customer</th><th>Items</th><th>Payment</th><th>Payable</th><th>Delivery contact & address</th><th>Fulfilment</th></tr></thead><tbody>{data.map((order) => { const current = order.deliveryStatus; const index = stages.indexOf(current); const next = index >= 0 && order.status !== 'CANCELLED' ? stages[index + 1] : null; return <tr key={order.id}><td><b>#{order.id}</b><small>{order.customerName || `Customer #${order.userId}`} · {date(order.createdAt)}</small></td><td>{order.items?.map((item) => <small key={item.productId}>{item.quantity} × {item.productName}</small>)}</td><td>{order.paymentStatus || 'PENDING'}</td><td><small>Items {moneyExact(order.subtotalAmount)}</small><small>Delivery {moneyExact(order.deliveryFee)}</small><b>{moneyExact(order.totalAmount)}</b></td><td>{order.delivery ? <><b>{order.delivery.fullName}</b><small>{order.delivery.mobileNumber}</small><small>{order.delivery.address}, {order.delivery.city} {order.delivery.pinCode}</small>{order.delivery.deliveryInstructions && <small>Instructions: {order.delivery.deliveryInstructions}</small>}</> : 'Not provided'}</td><td><Badge tone={current === 'DELIVERED' ? 'success' : 'warning'}>{current || order.status}</Badge>{next && <select aria-label={`Update delivery status for order ${order.id}`} value={current} onChange={(e) => updateStatus(order.id, e.target.value)}><option value={current}>{current}</option><option value={next}>{next}</option></select>}{current === 'OUT_FOR_DELIVERY' && <Button size="small" onClick={() => updateStatus(order.id, 'DELIVERED')}>Mark delivered</Button>}</td></tr>; })}</tbody></table></div></Card>}</>;
}
