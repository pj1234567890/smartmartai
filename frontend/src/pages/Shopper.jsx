import { useEffect, useState } from 'react';
import { api } from '../services/api';
import { money, moneyExact, date } from '../utils/formatters';
import { Badge, Button, Card, EmptyState, ErrorState, Skeleton } from '../components/common/Ui';
import { PageHeader } from '../components/layout/Layout';
import { ProductCard } from '../components/products/ProductCard';
import { ChatWindow } from '../components/ai/ChatWindow';

function useLoad(load, deps = []) {
  const [state, setState] = useState({
    loading: true,
    data: null,
    error: ''
  });

  const refresh = () => {
    setState({
      loading: true,
      data: null,
      error: ''
    });

    load()
        .then((data) => {
          setState({
            loading: false,
            data,
            error: ''
          });
        })
        .catch((e) => {
          setState({
            loading: false,
            data: null,
            error: e.message
          });
        });
  };

  useEffect(() => {
    refresh();
  }, deps);

  return {
    ...state,
    refresh
  };
}

function ProductsView({ toast, go, home = false, onCartChange }) {
  const [query, setQuery] = useState('');
  const [filters, setFilters] = useState({});

  const {
    data,
    loading,
    error,
    refresh
  } = useLoad(
      () => api.products(filters),
      [filters.name, filters.category]
  );

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
    try {
      await api.addWishlist(id);
      wishlist.refresh();
      toast('Saved to your wishlist');
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const apply = (e) => {
    e?.preventDefault();
    setFilters({ name: query });
  };

  if (error) {
    return <ErrorState error={error} retry={refresh} />;
  }

  const items = home ? products.slice(0, 4) : products;

  return (
      <>
        {!home && (
            <PageHeader
                eyebrow="PRODUCT DISCOVERY"
                title="Explore products"
                text="Find exactly what you need, with aisle and availability at a glance."
            />
        )}

        <form className="search-panel" onSubmit={apply}>
          <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="What are you looking for?"
              aria-label="Search products"
          />

          <select
              value={filters.category || ''}
              onChange={(e) =>
                  setFilters({
                    ...filters,
                    category: e.target.value
                  })
              }
          >
            <option value="">All categories</option>

            {[...new Set(products.map((p) => p.category))].map((c) => (
                <option key={c}>{c}</option>
            ))}
          </select>

          <Button type="submit">Search</Button>

          {(filters.name || filters.category) && (
              <Button
                  variant="ghost"
                  type="button"
                  onClick={() => {
                    setQuery('');
                    setFilters({});
                  }}
              >
                Clear
              </Button>
          )}
        </form>

        {loading || inventory.loading ? (
            <div className="product-grid">
              <Skeleton
                  count={home ? 4 : 8}
                  className="product-skeleton"
              />
            </div>
        ) : items.length ? (
            <div className="product-grid">
              {items.map((product) => (
                  <ProductCard
                      key={product.id}
                      product={product}
                      inventory={inventory.data?.find(
                          (i) => i.productId === product.id
                      )}
                      wished={wishlist.data?.some(
                          (x) =>
                              x.productId === product.id ||
                              x.id === product.id
                      )}
                      onCart={add}
                      onWish={wish}
                  />
              ))}
            </div>
        ) : (
            <EmptyState
                title="No products found"
                text="Try adjusting your search or filters."
                action={
                  <Button
                      onClick={() => {
                        setQuery('');
                        setFilters({});
                      }}
                  >
                    Clear filters
                  </Button>
                }
            />
        )}

        {home && products.length > 4 && (
            <div className="section-link">
              <Button
                  variant="secondary"
                  onClick={() => go('products')}
              >
                Explore all products
              </Button>
            </div>
        )}
      </>
  );
}

export function ShopperPage({
                              page,
                              user,
                              toast,
                              go,
                              onCartChange
                            }) {
  if (page === 'products') {
    return (
        <ProductsView
            toast={toast}
            go={go}
            onCartChange={onCartChange}
        />
    );
  }

  if (page === 'wishlist') {
    return (
        <Wishlist
            toast={toast}
            go={go}
            onCartChange={onCartChange}
        />
    );
  }

  if (page === 'cart') {
    return (
        <Cart
            user={user}
            toast={toast}
            go={go}
            onCartChange={onCartChange}
        />
    );
  }

  if (page === 'orders') {
    return <Orders />;
  }

  if (page === 'recommendations') {
    return (
        <RecommendationsPage
            toast={toast}
            go={go}
            onCartChange={onCartChange}
        />
    );
  }

  if (page === 'ai') {
    return (
        <>
          <PageHeader
              eyebrow="AI SHOPPING ASSISTANT"
              title="A little help, right when you need it"
              text="Get real answers about the SmartMart catalogue."
          />
          <ChatWindow mode="shopper" />
        </>
    );
  }

  return (
      <ShopperHome
          user={user}
          toast={toast}
          go={go}
          onCartChange={onCartChange}
      />
  );
}

function RecommendationsPage({
                               toast,
                               go,
                               onCartChange
                             }) {
  const {
    data,
    loading,
    error,
    refresh
  } = useLoad(api.recommendations);

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
    try {
      await api.addWishlist(id);
      wishlist.refresh();
      toast('Saved to your wishlist');
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  /*
   * The backend returns a direct array:
   *
   * [
   *   { id: 2, name: 'Basmati Rice 5kg', ... },
   *   ...
   * ]
   *
   * Also support a paginated response just in case.
   */
  const products = Array.isArray(data)
      ? data
      : Array.isArray(data?.content)
          ? data.content
          : [];

  return (
      <>
        <PageHeader
            eyebrow="PICKED FOR YOU"
            title="Recommendations"
            text="Suggestions based on your orders, wishlist, active offers, and live availability."
            action={
              <Button
                  variant="ghost"
                  onClick={refresh}
              >
                Refresh
              </Button>
            }
        />

        {loading ? (
            <div className="product-grid">
              <Skeleton
                  count={8}
                  className="product-skeleton"
              />
            </div>
        ) : error ? (
            <ErrorState
                error={error}
                retry={refresh}
            />
        ) : products.length === 0 ? (
            <EmptyState
                title="No recommendations yet"
                text="Complete an order or save a few wishlist items and we'll suggest products for you."
                action={
                  <Button onClick={() => go('products')}>
                    Explore products
                  </Button>
                }
            />
        ) : (
            <div className="product-grid">
              {products.map((product) => (
                  <ProductCard
                      key={product.id}
                      product={product}
                      inventory={{
                        availableQuantity:
                            Number(product.availableQuantity ?? 0),
                        quantity:
                            Number(product.availableQuantity ?? 0),
                        discountPercentage:
                            Number(product.discountPercentage ?? 0),
                        reorderLevel: 0
                      }}
                      wished={wishlist.data?.some(
                          (x) =>
                              x.productId === product.id ||
                              x.id === product.id
                      )}
                      onCart={add}
                      onWish={wish}
                  />
              ))}
            </div>
        )}
      </>
  );
}

function RecommendationStrip({
                               toast,
                               onCartChange
                             }) {
  const {
    data,
    loading,
    error,
    refresh
  } = useLoad(api.recommendations);

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
    try {
      await api.addWishlist(id);
      wishlist.refresh();
      toast('Saved to your wishlist');
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  /*
   * IMPORTANT:
   * /api/recommendations returns a DIRECT ARRAY.
   */
  const products = Array.isArray(data)
      ? data
      : Array.isArray(data?.content)
          ? data.content
          : [];

  if (loading) {
    return (
        <section className="recommendation-section">
          <div className="section-heading">
            <div>
              <p className="eyebrow">SMART SUGGESTIONS</p>
              <h2>Recommended for you</h2>
            </div>
          </div>

          <div className="product-grid">
            <Skeleton
                count={4}
                className="product-skeleton"
            />
          </div>
        </section>
    );
  }

  if (error) {
    return (
        <section className="section-heading">
          <div>
            <p className="eyebrow">SMART SUGGESTIONS</p>
            <h2>Recommended for you</h2>
            <p className="muted">
              Recommendations are temporarily unavailable.
            </p>

            <Button
                variant="ghost"
                onClick={refresh}
            >
              Try again
            </Button>
          </div>
        </section>
    );
  }

  if (!products.length) {
    return null;
  }

  return (
      <section className="recommendation-section">
        <div className="section-heading">
          <div>
            <p className="eyebrow">SMART SUGGESTIONS</p>
            <h2>Recommended for you</h2>
            <p className="muted">
              Personalized suggestions based on your shopping activity.
            </p>
          </div>
        </div>

        <div className="product-grid">
          {products.map((product) => (
              <ProductCard
                  key={product.id}
                  product={product}
                  inventory={{
                    availableQuantity:
                        Number(product.availableQuantity ?? 0),
                    quantity:
                        Number(product.availableQuantity ?? 0),
                    discountPercentage:
                        Number(product.discountPercentage ?? 0),
                    reorderLevel: 0
                  }}
                  wished={wishlist.data?.some(
                      (x) =>
                          x.productId === product.id ||
                          x.id === product.id
                  )}
                  onCart={add}
                  onWish={wish}
              />
          ))}
        </div>
      </section>
  );
}

function ShopperHome({
                       user,
                       toast,
                       go,
                       onCartChange
                     }) {
  const hour = new Date().getHours();

  const greeting =
      hour < 12
          ? 'Good morning'
          : hour < 18
              ? 'Good afternoon'
              : 'Good evening';

  return (
      <>
        <section className="shopper-hero">
          <div>
            <p className="eyebrow">
              {greeting.toUpperCase()} · {user.name}
            </p>

            <h1>Your smarter way to shop.</h1>

            <p>
              Find what you need, know where it is,
              and make every trip a little easier.
            </p>

            <div
                className="hero-search"
                onClick={() => go('products')}
                role="button"
                tabIndex="0"
            >
              What are you looking for?
              <span>Search</span>
            </div>
          </div>

          <div className="hero-orbit">
            <b>✦</b>
            <span>Simple. Smart. Local.</span>
          </div>
        </section>

        <div className="quick-actions">
          {[
            ['Explore', 'Browse products'],
            ['Wishlist', 'Saved for later'],
            ['Cart', 'Ready to checkout'],
            ['Ask AI', 'Find it faster']
          ].map(([a, b], i) => (
              <button
                  onClick={() =>
                      go(
                          [
                            'products',
                            'wishlist',
                            'cart',
                            'ai'
                          ][i]
                      )
                  }
                  key={a}
              >
                <span>0{i + 1}</span>
                <b>{a}</b>
                <small>{b}</small>
              </button>
          ))}
        </div>

        <section className="section-heading">
          <div>
            <p className="eyebrow">SHOP THE STORE</p>
            <h2>Explore products</h2>
          </div>
        </section>

        <ProductsView
            home
            toast={toast}
            go={go}
            onCartChange={onCartChange}
        />

        <RecommendationStrip
            toast={toast}
            onCartChange={onCartChange}
        />

        <section className="ai-cta">
          <div>
            <p className="eyebrow">SMARTMART AI</p>
            <h2>Not sure where to start?</h2>
            <p>
              Ask about availability, aisles, and active
              offers in natural language.
            </p>

            <Button
                variant="light"
                onClick={() => go('ai')}
            >
              Ask the assistant
            </Button>
          </div>

          <span>✦</span>
        </section>
      </>
  );
}

function Wishlist({
                    toast,
                    go,
                    onCartChange
                  }) {
  const {
    data,
    loading,
    error,
    refresh
  } = useLoad(api.wishlist);

  const add = async (item) => {
    try {
      await api.addCart(item.productId);
      onCartChange?.();
      toast('Added to your cart');
      refresh();
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const remove = async (id) => {
    try {
      await api.removeWishlist(id);
      toast('Removed from your wishlist');
      refresh();
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  return (
      <>
        <PageHeader
            eyebrow="SAVED PRODUCTS"
            title="My Wishlist"
            text="Save products here and we'll keep them handy."
        />

        {loading ? (
            <Skeleton
                count={3}
                className="row-skeleton"
            />
        ) : error ? (
            <ErrorState
                error={error}
                retry={refresh}
            />
        ) : !data?.length ? (
            <EmptyState
                title="Your wishlist is waiting."
                text="Save products here and we'll keep them handy."
                action={
                  <Button onClick={() => go('products')}>
                    Explore products
                  </Button>
                }
            />
        ) : (
            <div className="list-stack">
              {data.map((item) => {
                const status =
                    item.availabilityStatus ||
                    (
                        item.availableQuantity === 0
                            ? 'OUT_OF_STOCK'
                            : item.availableQuantity <= 2
                                ? 'LOW_STOCK'
                                : 'AVAILABLE'
                    );

                const statusTone =
                    status === 'OUT_OF_STOCK'
                        ? 'danger'
                        : status === 'LOW_STOCK'
                            ? 'warning'
                            : 'success';

                const discount =
                    Number(item.discountPercentage || 0);

                const isOutOfStock =
                    status === 'OUT_OF_STOCK';

                return (
                    <Card
                        className="saved-row"
                        key={item.productId || item.id}
                    >
                      <div className="mini-art">
                        ✦
                      </div>

                      <div>
                        <p className="product-brand">
                          {item.brand}
                        </p>

                        <h3>{item.name}</h3>

                        <p>{money(item.price)}</p>

                        {discount > 0 && (
                            <p className="muted">
                              {discount}% active discount
                            </p>
                        )}

                        {item.aisle && (
                            <p className="muted">
                              Aisle {item.aisle} · Shelf{' '}
                              {item.shelf || '—'}
                            </p>
                        )}
                      </div>

                      <Badge tone={statusTone}>
                        {status === 'AVAILABLE'
                            ? 'AVAILABLE'
                            : status === 'LOW_STOCK'
                                ? 'LOW STOCK'
                                : 'OUT OF STOCK'}
                      </Badge>

                      {isOutOfStock ? (
                          <span className="muted">
                    Out of stock
                  </span>
                      ) : (
                          <Button
                              onClick={() => add(item)}
                          >
                            Add to cart
                          </Button>
                      )}

                      <Button
                          variant="ghost"
                          onClick={() =>
                              remove(
                                  item.productId || item.id
                              )
                          }
                      >
                        Remove
                      </Button>
                    </Card>
                );
              })}
            </div>
        )}
      </>
  );
}

function Cart({
                user,
                toast,
                go,
                onCartChange
              }) {
  const {
    data,
    loading,
    error,
    refresh
  } = useLoad(api.cart);

  const [checkingOut, setCheckingOut] =
      useState(false);
  const [deliveryStep, setDeliveryStep] = useState(false);
  const [delivery, setDelivery] = useState({ fullName: user?.name || '', mobileNumber: '', address: '', city: '', pinCode: '', deliveryInstructions: '' });

  const modify = async (
      id,
      quantity
  ) => {
    try {
      if (quantity < 1) {
        await api.removeCart(id);
      } else {
        await api.updateCart(id, quantity);
      }

      refresh();
      onCartChange?.();
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const beginCheckout = async () => {
    try {
      const profile = await api.deliveryProfile();
      setDelivery((current) => ({ ...current, ...Object.fromEntries(Object.entries(profile || {}).filter(([, value]) => value != null)) }));
    } catch (e) {
      toast(e.message, 'error');
    }
    setDeliveryStep(true);
  };

  const checkout = async (event) => {
    event.preventDefault();
    setCheckingOut(true);

    try {
      await api.checkout(delivery);
      toast(
          'Checkout successful — your order is on its way'
      );
      onCartChange?.();
      go('orders');
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setCheckingOut(false);
    }
  };

  if (loading) {
    return (
        <Skeleton
            count={3}
            className="row-skeleton"
        />
    );
  }

  if (error) {
    return (
        <ErrorState
            error={error}
            retry={refresh}
        />
    );
  }

  if (!data?.items?.length) {
    return (
        <>
          <PageHeader
              eyebrow="YOUR SHOPPING BAG"
              title="Your cart is empty"
              text="Add a few essentials and they'll be ready here."
          />

          <EmptyState
              title="Ready when you are"
              text="Discover products and build your next basket."
              action={
                <Button onClick={() => go('products')}>
                  Explore products
                </Button>
              }
          />
        </>
    );
  }

  return (
      <>
        <PageHeader
            eyebrow="YOUR SHOPPING BAG"
            title="Cart"
            text={`${data.items.length} item${
                data.items.length === 1 ? '' : 's'
            } selected`}
        />

        <div className="cart-layout">
          <div className="list-stack">
            {data.items.map((item) => (
                <Card
                    className="cart-row"
                    key={item.productId}
                >
                  <div className="mini-art">
                    ◌
                  </div>

                  <div>
                    <h3>{item.name}</h3>

                    <p>
                      {money(item.unitPrice)} each ·{' '}
                      {item.available} available
                    </p>

                    <button
                        className="text-btn"
                        onClick={() =>
                            modify(item.productId, 0)
                        }
                    >
                      Remove
                    </button>
                  </div>

                  <div className="quantity">
                    <button
                        onClick={() =>
                            modify(
                                item.productId,
                                item.quantity - 1
                            )
                        }
                        aria-label="Decrease quantity"
                    >
                      −
                    </button>

                    <b>{item.quantity}</b>

                    <button
                        onClick={() =>
                            modify(
                                item.productId,
                                item.quantity + 1
                            )
                        }
                        disabled={
                            item.quantity >= item.available
                        }
                        aria-label="Increase quantity"
                    >
                      +
                    </button>
                  </div>

                  <strong>
                    {money(item.subtotal)}
                  </strong>
                </Card>
            ))}
          </div>

          <Card className="summary">
            <h2>Order summary</h2>

            <div>
              <span>Subtotal</span>
              <b>{money(data.total)}</b>
            </div>
            <div><span>Delivery fee · Store delivery</span><b>{moneyExact(data.deliveryFee)}</b></div>

            {deliveryStep ? <form onSubmit={checkout} className="list-stack">
              <h3>Delivery details</h3>
              <label>Full name<input autoComplete="name" required maxLength="120" value={delivery.fullName} onChange={(e) => setDelivery({ ...delivery, fullName: e.target.value })} /></label>
              <label>Mobile number<input autoComplete="tel" type="tel" required pattern="[+0-9()\s-]{10,24}" title="Enter a valid mobile number" value={delivery.mobileNumber} onChange={(e) => setDelivery({ ...delivery, mobileNumber: e.target.value })} /></label>
              <label>Complete delivery address<textarea autoComplete="street-address" required maxLength="500" rows="2" value={delivery.address} onChange={(e) => setDelivery({ ...delivery, address: e.target.value })} /></label>
              <label>City<input autoComplete="address-level2" required maxLength="120" value={delivery.city} onChange={(e) => setDelivery({ ...delivery, city: e.target.value })} /></label>
              <label>PIN code<input autoComplete="postal-code" inputMode="numeric" required pattern="[0-9]{6}" maxLength="6" title="Enter a 6 digit PIN code" value={delivery.pinCode} onChange={(e) => setDelivery({ ...delivery, pinCode: e.target.value })} /></label>
              <label>Delivery instructions (optional)<textarea maxLength="500" rows="2" value={delivery.deliveryInstructions} onChange={(e) => setDelivery({ ...delivery, deliveryInstructions: e.target.value })} /></label>
              <div className="delivery-review"><b>Delivering to</b><p>{delivery.fullName || 'Name'} · {delivery.mobileNumber || 'Mobile'}<br />{delivery.address || 'Address'}<br />{delivery.city || 'City'} {delivery.pinCode}</p>{delivery.deliveryInstructions && <p>Instructions: {delivery.deliveryInstructions}</p>}</div>
              <Button loading={checkingOut}>Place Order</Button>
              <Button type="button" variant="ghost" onClick={() => setDeliveryStep(false)}>Back to cart</Button>
            </form> : <>
              <div><span>Delivery</span><b>Details at next step</b></div>
              <Button onClick={beginCheckout}>Continue to delivery</Button>
            </>}

            <hr />

            <div className="summary-total">
              <span>Payable total</span>
              <strong>{moneyExact(data.totalPayable)}</strong>
            </div>

            <small>
              Stock is confirmed when you place your order.
            </small>
          </Card>
        </div>
      </>
  );
}

function Orders() {
  const {
    data,
    loading,
    error,
    refresh
  } = useLoad(api.orders);

  return (
      <>
        <PageHeader
            eyebrow="PURCHASE HISTORY"
            title="Orders"
            text="A record of your recent SmartMart orders."
            action={<Button variant="secondary" onClick={refresh}>Refresh status</Button>}
        />

        {loading ? (
            <Skeleton
                count={3}
                className="row-skeleton"
            />
        ) : error ? (
            <ErrorState
                error={error}
                retry={refresh}
            />
        ) : !data?.length ? (
            <EmptyState
                title="No orders yet"
                text="Your completed orders will appear here."
            />
        ) : (
            <div className="list-stack">
              {data.map((order) => (
                  <Card
                      className="order-row"
                      key={order.id}
                  >
                    <div>
                      <p className="eyebrow">
                        ORDER #{order.id}
                      </p>

                      <h3>
                        {date(order.createdAt)}
                      </h3>

                      <p>
                        {order.items?.length || 0} item
                        {order.items?.length === 1
                            ? ''
                            : 's'}{' '}
                        ·{' '}
                        {order.items
                            ?.map(
                                (i) => i.productName
                            )
                            .join(', ')}
                      </p>
                      {order.delivery && <p className="order-delivery"><b>Delivery to</b><br />{order.delivery.fullName} · {order.delivery.mobileNumber}<br />{order.delivery.address}, {order.delivery.city} {order.delivery.pinCode}{order.delivery.deliveryInstructions && <><br />Instructions: {order.delivery.deliveryInstructions}</>}</p>}
                    </div>

                    <div><Badge tone={order.deliveryStatus === 'DELIVERED' ? 'success' : 'warning'}>{order.deliveryStatus || order.status}</Badge><small>Payment: {order.paymentStatus || 'PENDING'}</small></div>

                    <div><small>Items {moneyExact(order.subtotalAmount)} · Delivery {moneyExact(order.deliveryFee)}</small><strong>{moneyExact(order.totalAmount)}</strong></div>
                  </Card>
              ))}
            </div>
        )}
      </>
  );
}
