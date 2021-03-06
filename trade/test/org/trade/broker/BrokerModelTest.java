/* ===========================================================
 * TradeManager : a application to trade strategies for the Java(tm) platform
 * ===========================================================
 *
 * (C) Copyright 2011-2011, by Simon Allen and Contributors.
 *
 * Project Info:  org.trade
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * [Java is a trademark or registered trademark of Oracle, Inc.
 * in the United States and other countries.]
 *
 * (C) Copyright 2011-2011, by Simon Allen and Contributors.
 *
 * Original Author:  Simon Allen;
 * Contributor(s):   -;
 *
 * Changes
 * -------
 *
 */
package org.trade.broker;

import static org.junit.Assert.*;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.Timer;

import org.jfree.data.DataUtilities;
import org.jfree.data.time.RegularTimePeriod;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trade.broker.client.Broker;
import org.trade.core.factory.ClassFactory;
import org.trade.core.properties.ConfigProperties;
import org.trade.core.valuetype.Money;
import org.trade.dictionary.valuetype.Action;
import org.trade.dictionary.valuetype.OrderStatus;
import org.trade.dictionary.valuetype.OrderType;
import org.trade.persistent.dao.TradeOrder;
import org.trade.persistent.dao.TradePosition;
import org.trade.persistent.dao.Tradestrategy;
import org.trade.persistent.dao.TradestrategyTest;
import org.trade.strategy.data.IndicatorSeries;
import org.trade.strategy.data.StrategyData;
import org.trade.strategy.data.candle.CandleItem;
import org.trade.strategy.data.movingaverage.MovingAverageItem;
import org.trade.strategy.data.vwap.VwapItem;
import org.trade.ui.TradeAppLoadConfig;
import org.trade.ui.base.BasePanel;

/**
 * Some tests for the {@link DataUtilities} class.
 * 
 * @author Simon Allen
 * @version $Revision: 1.0 $
 */
public class BrokerModelTest implements BrokerChangeListener {

	private final static Logger _log = LoggerFactory
			.getLogger(BrokerModelTest.class);

	private String symbol = "TEST";
	private BrokerModel backTestbrokerModel;
	private BigDecimal price = new BigDecimal(108.85);
	private Tradestrategy tradestrategy = null;
	private static Integer port = null;
	private static String host = null;
	private static Integer clientId;
	private static Timer timer = null;
	private boolean connectionFailed = false;
	private static AtomicInteger timerRunning = null;
	private final static Object lockCoreUtilsTest = new Object();
	private final static String _broker = BrokerModel._brokerTest;

	/**
	 * Method setUpBeforeClass.
	 * 
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		TradeAppLoadConfig.loadAppProperties();
		clientId = ConfigProperties.getPropAsInt("trade.tws.clientId");
		port = new Integer(ConfigProperties.getPropAsString("trade.tws.port"));
		host = ConfigProperties.getPropAsString("trade.tws.host");

		timer = new Timer(250, new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				synchronized (lockCoreUtilsTest) {
					timerRunning.addAndGet(250);
					lockCoreUtilsTest.notifyAll();
				}
			}
		});
	}

	/**
	 * Method setUp.
	 * 
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		try {
			this.tradestrategy = TradestrategyTest.getTestTradestrategy(symbol);
			backTestbrokerModel = (BrokerModel) ClassFactory
					.getServiceForInterface(_broker, BrokerModelTest.class);
			backTestbrokerModel.onConnect(host, port, clientId);
			assertNotNull(this.tradestrategy);

			backTestbrokerModel = (BrokerModel) ClassFactory
					.getServiceForInterface(_broker, BrokerModelTest.class);
			backTestbrokerModel.onConnect(host, port, clientId);

			timerRunning = new AtomicInteger(0);
			timer.start();
			// Note isConnected always returns false for the
			// BackTestBrokerModel.
			synchronized (lockCoreUtilsTest) {
				while (backTestbrokerModel.isConnected() && !connectionFailed) {
					lockCoreUtilsTest.wait();
				}
			}
			timer.stop();
			if (backTestbrokerModel.isConnected())
				_log.warn("Could not connect to TWS test will be ignored.",
						backTestbrokerModel.isConnected());

		} catch (Exception e) {
			fail("Error on setup " + e.getMessage());
		}
	}

	/**
	 * Method tearDown.
	 * 
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {

		// Wait for the BackTestBroker to complete. These tests use the testing
		// client from org.trade.brokerclient that runs its own thread.
		Broker backTestBroker = backTestbrokerModel
				.getBackTestBroker(this.tradestrategy);
		if (null != backTestBroker) {
			// Ping the broker to see if its completed. Not isConnected always
			// returns false for BackTestBrokerModel.
			timer.start();
			synchronized (lockCoreUtilsTest) {
				while (!backTestbrokerModel.isConnected() && !connectionFailed
						&& !backTestBroker.isDone()) {
					lockCoreUtilsTest.wait();
				}
			}
			timer.stop();
		}

		if (backTestbrokerModel.isConnected())
			backTestbrokerModel.onDisconnect();

		TradestrategyTest.clearDBData();
	}

	/**
	 * Method tearDownAfterClass.
	 * 
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Test
	public void testSubmitBuyOrder() {

		try {

			TradeOrder tradeOrder = new TradeOrder(this.tradestrategy,
					Action.BUY, OrderType.STPLMT, 100, price,
					price.add(new BigDecimal(0.02)), new Date());
			tradeOrder.setClientId(clientId);
			tradeOrder.setTransmit(new Boolean(true));
			tradeOrder.setStatus(OrderStatus.UNSUBMIT);

			tradeOrder = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder);

			_log.info("IdTradeOrder: " + tradeOrder.getIdTradeOrder()
					+ " OrderKey: " + tradeOrder.getOrderKey());
			assertNotNull(tradeOrder);

		} catch (Exception e) {
			fail("Error testSubmitBuyOrder Msg: " + e.getMessage());
		}
	}

	@Test
	public void testSubmitSellShortOrder() {

		try {
			_log.info("Symbol: " + this.tradestrategy.getContract().getSymbol());

			TradeOrder tradeOrder = new TradeOrder(this.tradestrategy,
					Action.SELL, OrderType.STPLMT, 100,
					price.subtract(new BigDecimal(0.70)),
					price.subtract(new BigDecimal(0.73)), new Date());

			tradeOrder.setClientId(clientId);
			tradeOrder.setTransmit(new Boolean(true));
			tradeOrder.setStatus(OrderStatus.UNSUBMIT);
			tradeOrder = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder);

			_log.info("IdTradeOrder: " + tradeOrder.getIdTradeOrder()
					+ " OrderKey: " + tradeOrder.getOrderKey());
			assertNotNull(tradeOrder.getIdTradeOrder());

		} catch (Exception e) {
			fail("Error testSubmitSellShortOrder Msg: " + e.getMessage());
		}
	}

	@Test
	public void testSubmitComboOrder() {

		try {
			String ocaID = new String(Integer.toString((new BigDecimal(Math
					.random() * 1000000)).intValue()));

			TradeOrder tradeOrder = new TradeOrder(this.tradestrategy,
					Action.SELL, OrderType.LMT, 50, null,
					price.add(new BigDecimal(1.0)), new Date());

			tradeOrder.setClientId(clientId);
			tradeOrder.setOcaType(2);
			tradeOrder.setOcaGroupName(ocaID);
			tradeOrder.setTransmit(true);
			tradeOrder.setStatus(OrderStatus.UNSUBMIT);
			tradeOrder = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder);
			assertNotNull(tradeOrder.getIdTradeOrder());

			TradeOrder tradeOrder1 = new TradeOrder(this.tradestrategy,
					Action.SELL, OrderType.LMT, 50,
					price.subtract(new BigDecimal(1.0)),
					price.add(new BigDecimal(2.0)), new Date());

			tradeOrder1.setClientId(clientId);
			tradeOrder1.setOcaType(2);
			tradeOrder1.setOcaGroupName(ocaID);
			tradeOrder1.setTransmit(false);
			tradeOrder1.setStatus(OrderStatus.UNSUBMIT);

			tradeOrder1 = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder1);
			assertNotNull(tradeOrder1.getIdTradeOrder());

			TradeOrder tradeOrder2 = new TradeOrder(this.tradestrategy,
					Action.SELL, OrderType.STP, 50,
					price.subtract(new BigDecimal(1.0)), null, new Date());
			ocaID = ocaID + "abc";
			tradeOrder2.setClientId(clientId);
			tradeOrder2.setOcaType(2);
			tradeOrder2.setOcaGroupName(ocaID);
			tradeOrder2.setTransmit(true);
			tradeOrder2.setStatus(OrderStatus.UNSUBMIT);
			tradeOrder2 = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder2);
			assertNotNull(tradeOrder2.getIdTradeOrder());

			TradeOrder tradeOrder3 = new TradeOrder(this.tradestrategy,
					Action.SELL, OrderType.STP, 50,
					price.subtract(new BigDecimal(2.0)), null, new Date());
			tradeOrder3.setClientId(clientId);
			tradeOrder3.setOcaType(2);
			tradeOrder3.setOcaGroupName(ocaID);
			tradeOrder3.setTransmit(false);
			tradeOrder3.setStatus(OrderStatus.UNSUBMIT);
			tradeOrder3 = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder3);
			assertNotNull(tradeOrder3.getIdTradeOrder());
			_log.info("IdTradeOrder: " + tradeOrder3.getIdTradeOrder()
					+ " OrderKey2: " + tradeOrder2.getOrderKey()
					+ " OrderKey2 Price: " + tradeOrder2.getLimitPrice()
					+ " OrderKey3: " + tradeOrder3.getOrderKey()
					+ " OrderKey3 Price: " + tradeOrder3.getAuxPrice());
			// Update the Stop price
			tradeOrder2.setAuxPrice(price.subtract(new BigDecimal(0.9)));
			tradeOrder2.setStatus(OrderStatus.UNSUBMIT);
			tradeOrder2 = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder2);

			tradeOrder3.setAuxPrice(price.subtract(new BigDecimal(0.9)));
			tradeOrder3.setStatus(OrderStatus.UNSUBMIT);
			tradeOrder3 = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder3);
			_log.info("IdTradeOrder: " + tradeOrder3.getIdTradeOrder()
					+ " OrderKey2: " + tradeOrder2.getOrderKey()
					+ " OrderKey2 Price: " + tradeOrder2.getLimitPrice()
					+ " OrderKey3: " + tradeOrder3.getOrderKey()
					+ " OrderKey3 Price: " + tradeOrder3.getAuxPrice());

			tradeOrder3.setTransmit(new Boolean(true));
			tradeOrder3.setStatus(OrderStatus.UNSUBMIT);
			tradeOrder3 = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder3);

			_log.info("IdTradeOrder: " + tradeOrder2.getIdTradeOrder()
					+ " OrderKey: " + tradeOrder3.getOrderKey());
			assertNotNull(tradeOrder3);

		} catch (Exception e) {
			fail("Error testSubmitComboOrder Msg: " + e.getMessage());
		}
	}

	@Test
	public void testOnBrokerData() {

		try {

			StrategyData.doDummyData(this.tradestrategy.getStrategyData()
					.getCandleDataset().getSeries(0),
					tradestrategy.getTradingday(),
					tradestrategy.getChartDays(), tradestrategy.getBarSize(),
					true, 0);
			backTestbrokerModel.setBrokerDataOnly(true);
			backTestbrokerModel.onBrokerData(tradestrategy, tradestrategy
					.getTradingday().getClose());

			assertFalse(this.tradestrategy.getStrategyData().getCandleDataset()
					.getSeries(0).isEmpty());

			IndicatorSeries candleseries = this.tradestrategy.getStrategyData()
					.getCandleDataset().getSeries(0);
			IndicatorSeries sma1Series = this.tradestrategy.getStrategyData()
					.getIndicatorByType(IndicatorSeries.MovingAverageSeries)
					.getSeries(0);
			IndicatorSeries sma2Series = this.tradestrategy.getStrategyData()
					.getIndicatorByType(IndicatorSeries.MovingAverageSeries)
					.getSeries(1);
			IndicatorSeries vwapSeries = this.tradestrategy.getStrategyData()
					.getIndicatorByType(IndicatorSeries.VwapSeries)
					.getSeries(0);
			IndicatorSeries heikinAshiSeries = this.tradestrategy
					.getStrategyData()
					.getIndicatorByType(IndicatorSeries.HeikinAshiSeries)
					.getSeries(0);

			for (int i = 0; i < candleseries.getItemCount(); i++) {
				CandleItem candle = (CandleItem) candleseries.getDataItem(i);
				RegularTimePeriod period = candle.getPeriod();

				CandleItem heikinAshiCandle = null;
				VwapItem vwap = null;
				MovingAverageItem sma1 = null;
				MovingAverageItem sma2 = null;

				int b = heikinAshiSeries.indexOf(period);
				if (b > -1) {
					heikinAshiCandle = (CandleItem) heikinAshiSeries
							.getDataItem(b);
				}

				int c = vwapSeries.indexOf(new Long(period
						.getMiddleMillisecond()));
				if (c > -1) {
					vwap = (VwapItem) vwapSeries.getDataItem(c);
				}
				int d = sma1Series.indexOf(new Long(period
						.getMiddleMillisecond()));
				if (d > -1) {
					sma1 = (MovingAverageItem) sma1Series.getDataItem(d);
				}
				int e = sma2Series.indexOf(new Long(period
						.getMiddleMillisecond()));
				if (e > -1) {
					sma2 = (MovingAverageItem) sma2Series.getDataItem(e);
				}
				if (null != candle) {
					_log.info("    Period Start: " + period.getStart()
							+ " Period End: " + period.getEnd() + " H: "
							+ new Money(candle.getHigh()) + " L: "
							+ new Money(candle.getLow()) + " O: "
							+ new Money(candle.getOpen()) + " C: "
							+ new Money(candle.getClose()) + " Vol: "
							+ new Money(candle.getVolume()) + " Vwap: "
							+ new Money(candle.getVwap()));
				}
				if (null != heikinAshiCandle) {
					_log.info("HA  Period Start: " + period.getStart()
							+ " Period End: " + period.getEnd() + " HA H: "
							+ new Money(heikinAshiCandle.getHigh()) + " HA L: "
							+ new Money(heikinAshiCandle.getLow()) + " HA O: "
							+ new Money(heikinAshiCandle.getOpen()) + " HA C: "
							+ new Money(heikinAshiCandle.getClose())
							+ " HA Vol: "
							+ new Money(heikinAshiCandle.getVolume())
							+ " HA Vwap: "
							+ new Money(heikinAshiCandle.getVwap()));
				}
				if (null != vwap) {
					_log.info("Vwp Period Start: " + period + " Vwap: "
							+ new Money(vwap.getY()));
				}
				if (null != sma1) {
					_log.info("S8  Period Start: " + period + " Sma 8: "
							+ new Money(sma1.getY()));
				}
				if (null != sma2) {
					_log.info("S20 Period Start: " + period + " Sma 20: "
							+ new Money(sma2.getY()));
				}
			}

		} catch (Exception e) {
			fail("Error testOnBrokerData Msg: " + e.getMessage());
		}
	}

	@Test
	public void testOnConnect() {
		try {
			backTestbrokerModel.onConnect(host, port, clientId);
			if (_broker.equals(BrokerModel._brokerTest)) {
				assertFalse(backTestbrokerModel.isConnected());
			} else {
				assertTrue(backTestbrokerModel.isConnected());
			}

		} catch (Exception ex) {
			fail("Error testOnConnect Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testDisconnect() {

		try {
			backTestbrokerModel.onDisconnect();
			if (_broker.equals(BrokerModel._brokerTest)) {
				assertFalse(backTestbrokerModel.isConnected());
			} else {
				assertTrue(backTestbrokerModel.isConnected());
			}
		} catch (Exception ex) {
			fail("Error testDisconnect Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testGetNextRequestId() {

		try {
			Integer id = backTestbrokerModel.getNextRequestId();
			assertNotNull(id);
		} catch (Exception ex) {
			fail("Error testGetNextRequestId Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnSubscribeAccountUpdates() {

		try {
			backTestbrokerModel.onSubscribeAccountUpdates(true, tradestrategy
					.getPortfolio().getIndividualAccount().getAccountNumber());
			assertFalse(backTestbrokerModel
					.isAccountUpdatesRunning(tradestrategy.getPortfolio()
							.getIndividualAccount().getAccountNumber()));

		} catch (Exception ex) {
			fail("Error testOnSubscribeAccountUpdates Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnCancelAccountUpdates() {

		try {
			backTestbrokerModel.onSubscribeAccountUpdates(true, tradestrategy
					.getPortfolio().getIndividualAccount().getAccountNumber());
			backTestbrokerModel.onCancelAccountUpdates(tradestrategy
					.getPortfolio().getIndividualAccount().getAccountNumber());
			assertFalse(backTestbrokerModel
					.isAccountUpdatesRunning(tradestrategy.getPortfolio()
							.getIndividualAccount().getAccountNumber()));
		} catch (Exception ex) {
			fail("Error testOnCancelAccountUpdates Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnReqManagedAccount() {

		try {
			backTestbrokerModel.onReqManagedAccount();
			assertFalse(backTestbrokerModel
					.isAccountUpdatesRunning(tradestrategy.getPortfolio()
							.getIndividualAccount().getAccountNumber()));
		} catch (Exception ex) {
			fail("Error testOnReqManagedAccount Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnReqAllOpenOrders() {

		try {
			backTestbrokerModel.onReqAllOpenOrders();
		} catch (Exception ex) {
			fail("Error testOnReqAllOpenOrders Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnReqOpenOrders() {

		try {
			backTestbrokerModel.onReqOpenOrders();
		} catch (Exception ex) {
			fail("Error testOnReqOpenOrders Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnReqRealTimeBars() {

		try {

			this.tradestrategy.getContract().addTradestrategy(
					this.tradestrategy);
			backTestbrokerModel.onReqRealTimeBars(
					this.tradestrategy.getContract(), false);
			assertFalse(backTestbrokerModel
					.isRealtimeBarsRunning(tradestrategy));
		} catch (Exception ex) {
			fail("Error testOnReqRealTimeBars Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnReqAllExecutions() {

		try {
			backTestbrokerModel.onReqAllExecutions(this.tradestrategy
					.getTradingday().getOpen());
		} catch (Exception ex) {
			fail("Error testOnReqAllExecutions Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnReqExecutions() {

		try {
			backTestbrokerModel.onReqExecutions(this.tradestrategy, false);
		} catch (Exception ex) {
			fail("Error testOnReqExecutions Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testIsRealtimeBarsRunning() {

		try {
			backTestbrokerModel.onCancelRealtimeBars(this.tradestrategy);
			assertFalse(backTestbrokerModel
					.isRealtimeBarsRunning(this.tradestrategy));
		} catch (Exception ex) {
			fail("Error testIsRealtimeBarsRunning Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testIsAccountUpdatesRunning() {

		try {
			backTestbrokerModel.onCancelAccountUpdates(tradestrategy
					.getPortfolio().getIndividualAccount().getAccountNumber());
			assertFalse(backTestbrokerModel
					.isAccountUpdatesRunning(tradestrategy.getPortfolio()
							.getIndividualAccount().getAccountNumber()));
		} catch (Exception ex) {
			fail("Error testIsRealtimeBarsRunning Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testIsHistoricalDataRunningTradestrategy() {

		try {
			backTestbrokerModel.onCancelBrokerData(this.tradestrategy);
			assertFalse(backTestbrokerModel
					.isHistoricalDataRunning(this.tradestrategy));
		} catch (Exception ex) {
			fail("Error testIsHistoricalDataRunning Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testIsHistoricalDataRunningContract() {

		try {
			backTestbrokerModel.onCancelBrokerData(this.tradestrategy
					.getContract());
			assertFalse(backTestbrokerModel
					.isHistoricalDataRunning(this.tradestrategy.getContract()));
		} catch (Exception ex) {
			fail("Error testIsHistoricalDataRunning Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnCancelAllRealtimeData() {

		try {
			backTestbrokerModel.onCancelAllRealtimeData();
			assertFalse(backTestbrokerModel
					.isRealtimeBarsRunning(this.tradestrategy));
		} catch (Exception ex) {
			fail("Error testOnCancelAllRealtimeData Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnCancelRealtimeBars() {

		try {
			backTestbrokerModel.onCancelRealtimeBars(this.tradestrategy);
			assertFalse(backTestbrokerModel
					.isRealtimeBarsRunning(this.tradestrategy));
		} catch (Exception ex) {
			fail("Error testOnCancelRealtimeBars Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnCancelBrokerData() {

		try {
			backTestbrokerModel.onCancelBrokerData(this.tradestrategy);
			assertFalse(backTestbrokerModel
					.isHistoricalDataRunning(this.tradestrategy));
		} catch (Exception ex) {
			fail("Error testOnCancelBrokerData Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnCancelContractDetails() {

		try {
			backTestbrokerModel.onCancelContractDetails(this.tradestrategy
					.getContract());
		} catch (Exception ex) {
			fail("Error testOnCancelContractDetails Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnContractDetails() {

		try {
			backTestbrokerModel.onContractDetails(this.tradestrategy
					.getContract());
		} catch (Exception ex) {
			fail("Error testOnContractDetails Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testGetHistoricalData() {

		try {
			ConcurrentHashMap<Integer, Tradestrategy> historicalDataList = backTestbrokerModel
					.getHistoricalData();
			assertNotNull(historicalDataList);
		} catch (Exception ex) {
			fail("Error testGetHistoricalData Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnPlaceOrder() {

		try {
			TradeOrder tradeOrder = new TradeOrder(this.tradestrategy,
					Action.BUY, OrderType.MKT, 1000, null, null, new Date());
			tradeOrder = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder);
			assertNotNull(tradeOrder);
		} catch (Exception ex) {
			fail("Error testOnPlaceOrder Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testOnCancelOrder() {

		try {

			TradeOrder tradeOrder = new TradeOrder(this.tradestrategy,
					Action.BUY, OrderType.MKT, 1000, null, null, new Date());
			tradeOrder = backTestbrokerModel.onPlaceOrder(
					this.tradestrategy.getContract(), tradeOrder);
			assertNotNull(tradeOrder);
			backTestbrokerModel.onCancelOrder(tradeOrder);
		} catch (Exception ex) {
			fail("Error testOnCancelOrder Msg: " + ex.getMessage());
		}
	}

	@Test
	public void testIsBrokerDataOnly() {

		try {
			boolean result = backTestbrokerModel.isBrokerDataOnly();
			assertFalse(result);
		} catch (Exception ex) {
			fail("Error testIsBrokerDataOnly Msg: " + ex.getMessage());
		}
	}

	public void connectionOpened() {
		_log.info("Connection opened");
	}

	public void connectionClosed(boolean forced) {
		connectionFailed = true;
		_log.info("Connection closed");
	}

	/**
	 * Method executionDetailsEnd.
	 * 
	 * @param execDetails
	 *            ConcurrentHashMap<Integer,TradeOrder>
	 */
	public void executionDetailsEnd(
			ConcurrentHashMap<Integer, TradeOrder> execDetails) {

	}

	/**
	 * Method historicalDataComplete.
	 * 
	 * @param tradestrategy
	 *            Tradestrategy
	 */
	public void historicalDataComplete(Tradestrategy tradestrategy) {
	}

	/**
	 * Method managedAccountsUpdated.
	 * 
	 * @param accountNumber
	 *            String
	 */
	public void managedAccountsUpdated(String accountNumber) {

	}

	/**
	 * Method fAAccountsCompleted. Notifies all registered listeners that the
	 * brokerManagerModel has received all FA Accounts information.
	 * 
	 */
	public void fAAccountsCompleted() {

	}

	/**
	 * Method updateAccountTime.
	 * 
	 * @param accountNumber
	 *            String
	 */
	public void updateAccountTime(String accountNumber) {

	}

	/**
	 * Method brokerError.
	 * 
	 * @param brokerError
	 *            BrokerModelException
	 */
	public void brokerError(BrokerModelException ex) {
		if (502 == ex.getErrorCode()) {
			_log.info("TWS is not running test will not be run");
			return;
		}
		if (ex.getErrorId() == 1) {
			_log.error("Error: " + ex.getErrorCode(), ex.getMessage(), ex);
		} else if (ex.getErrorId() == 2) {
			_log.warn("Warning: " + ex.getMessage(), BasePanel.WARNING);
		} else if (ex.getErrorId() == 3) {
			_log.info("Information: " + ex.getMessage(), BasePanel.INFORMATION);
		} else {
			_log.error("Unknown Error Id Code: " + ex.getErrorCode(),
					ex.getMessage(), ex);
		}
	}

	/**
	 * Method tradeOrderFilled.
	 * 
	 * @param tradeOrder
	 *            TradeOrder
	 */
	public void tradeOrderFilled(TradeOrder tradeOrder) {

	}

	/**
	 * Method tradeOrderCancelled.
	 * 
	 * @param tradeOrder
	 *            TradeOrder
	 */
	public void tradeOrderCancelled(TradeOrder tradeOrder) {

	}

	/**
	 * Method tradeOrderStatusChanged.
	 * 
	 * @param tradeOrder
	 *            TradeOrder
	 */
	public void tradeOrderStatusChanged(TradeOrder tradeOrder) {

	}

	/**
	 * Method positionClosed.
	 * 
	 * @param trade
	 *            Trade
	 */
	public void positionClosed(TradePosition trade) {

	}

	/**
	 * Method openOrderEnd.
	 * 
	 * @param openOrders
	 *            ConcurrentHashMap<Integer,TradeOrder>
	 */
	public void openOrderEnd(ConcurrentHashMap<Integer, TradeOrder> openOrders) {

	}
}
