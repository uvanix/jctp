package com.github.jctp.broker;

import com.github.jctp.struct.Account;
import com.github.jctp.struct.CommissionRate;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.Investor;
import com.github.jctp.struct.MarginRate;
import com.github.jctp.struct.Order;
import com.github.jctp.struct.Position;
import com.github.jctp.struct.Product;
import com.github.jctp.struct.SettlementInfo;
import com.github.jctp.struct.Trade;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 交易网关。
 * <p>
 * queryXxx 为阻塞查询：CTP 限制同时只能有一个查询在途且每秒一次，遇到流控会自动重试至超时。
 * 不要在监听器（CTP 回调线程）里调用阻塞查询，否则会因等不到应答而超时。
 */
public interface CtpTdBroker extends CtpBroker {

    /**
     * 连接时查询的投资者信息，未连接时为 null。
     */
    Investor investor();

    /**
     * 最近一次 {@link #queryProducts()} 加载的全部产品；从未加载时为空。
     */
    Collection<Product> products();

    /**
     * 查询全部产品（品种）并替换 {@link #products()} 的缓存。connect 不会加载产品，需要时由调用方调用。
     */
    List<Product> queryProducts();

    /**
     * 查询全部合约并替换 {@link #instruments()} 的缓存。connect 不会加载合约，需要时由调用方调用；
     * 报单未填交易所代码时也依赖该缓存补全。
     */
    default List<Instrument> queryInstruments() {
        return queryInstruments(_ -> {
        });
    }

    /**
     * @param progress 已收到的合约数，在调用线程上约每 200ms 回调一次
     */
    List<Instrument> queryInstruments(IntConsumer progress);

    /**
     * 查询一个品种的合约，并替换缓存中该品种的合约。全量查询在盘中可能要数分钟，可按品种分批加载。
     */
    List<Instrument> queryInstruments(String exchangeId, String productId);

    /**
     * 查询单个合约并更新缓存；柜台没有该合约时为空。
     */
    Optional<Instrument> queryInstrument(String instrumentId);

    /**
     * 在 {@link #queryInstruments()} 加载的合约中查找。
     */
    Optional<Instrument> instrument(String instrumentId);

    /**
     * 最近一次 {@link #queryInstruments()} 加载的全部合约；从未加载时为空。
     */
    Collection<Instrument> instruments();

    /**
     * 报单。返回已分配 FrontID/SessionID/OrderRef 的报单，后续状态通过 {@link #setOrderListener} 推送；
     * 请求发送失败时返回 InsertRejected 状态的报单。
     *
     * @throws IllegalStateException 未连接或未就绪
     */
    Order submitOrder(Order order);

    /**
     * 撤单，按 {@link Order#orderId()} 或交易所报单编号定位。返回当前已知的报单；
     * 报单已终结时不发送请求，请求发送失败时返回 CancelRejected 状态的报单。
     *
     * @throws IllegalStateException 未连接或未就绪
     */
    Order cancelOrder(Order order);

    Optional<Order> order(String orderId);

    /**
     * 连接以来收到的报单。私有流按 RESUME 续传，进程重启前已收到的报单不会重发，需要时用 {@link #queryOrders()}。
     */
    Collection<Order> orders();

    /**
     * 连接以来收到的成交。进程重启前已收到的成交不会重发，需要时用 {@link #queryTrades()}。
     */
    Collection<Trade> trades();

    Account queryAccount();

    List<Position> queryPositions();

    /**
     * 查询当日报单。
     */
    List<Order> queryOrders();

    /**
     * 查询当日成交。
     */
    List<Trade> queryTrades();

    /**
     * 柜台可能只返回品种级费率，此时结果的 instrumentID 为品种代码。
     */
    Optional<CommissionRate> queryCommissionRate(String instrumentId);

    /**
     * 投机保证金率。
     */
    Optional<MarginRate> queryMarginRate(String instrumentId);

    /**
     * 查询结算单。
     *
     * @param tradingDay 交易日，为 null 时查询最近一次结算单
     * @return 结算单全文；该日没有结算单时为空
     */
    Optional<SettlementInfo> querySettlementInfo(LocalDate tradingDay);

    /**
     * 查询最近一次结算单。
     */
    default Optional<SettlementInfo> querySettlementInfo() {
        return querySettlementInfo(null);
    }

    /**
     * 连接进度回调（如"登录"、"查询投资者"），断线自动重连时也会再次回调。在 CTP 回调线程上调用，不要阻塞。
     */
    void setConnectListener(Consumer<String> stageListener);

    /**
     * 报单状态推送回调。在 CTP 回调线程上调用，接收方需自行切换线程。
     */
    void setOrderListener(Consumer<Order> orderConsumer);

    /**
     * 成交推送回调。在 CTP 回调线程上调用，接收方需自行切换线程。
     */
    void setTradeListener(Consumer<Trade> tradeConsumer);
}
