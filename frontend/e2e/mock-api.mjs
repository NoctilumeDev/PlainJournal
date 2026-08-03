import { createServer } from "node:http";

const port = Number(process.env.PLAIN_JOURNAL_MOCK_API_PORT ?? 18000);
const now = "2026-07-20T00:00:00Z";
const customer = {
  id: "2079000000000000999",
  email: "reader@example.com",
  displayName: "Reader",
  status: "ACTIVE",
  roles: ["CUSTOMER"],
};
const admin = {
  id: "2079000000000001999",
  email: "admin@example.com",
  displayName: "平台管理员",
  status: "ACTIVE",
  roles: ["ADMIN"],
};
const analyticsProductId = "2088000000000000101";
const analyticsDaily = [{
  businessDate: "2026-08-02",
  createdOrderCount: 5,
  createdOrderAmount: 945,
  paymentCount: 4,
  paymentAmount: 756,
  completedOrderCount: 3,
  completedOrderAmount: 567,
  closedOrderCount: 1,
  afterSaleCount: 0,
  afterSaleAmount: 0,
  refundCount: 0,
  refundAmount: 0,
  updatedAt: "2026-08-02T23:58:00Z",
}, {
  businessDate: "2026-08-03",
  createdOrderCount: 7,
  createdOrderAmount: 1323,
  paymentCount: 6,
  paymentAmount: 1134,
  completedOrderCount: 5,
  completedOrderAmount: 945,
  closedOrderCount: 1,
  afterSaleCount: 1,
  afterSaleAmount: 189,
  refundCount: 1,
  refundAmount: 189,
  updatedAt: "2026-08-03T08:00:00Z",
}];

function analyticsOverview(from, to, productLimit) {
  const daily = analyticsDaily.filter((summary) =>
    summary.businessDate >= from && summary.businessDate <= to);
  const sum = (key) => daily.reduce(
    (total, summary) => total + Number(summary[key] ?? 0),
    0,
  );
  const completedOrderCount = sum("completedOrderCount");
  const completedOrderAmount = sum("completedOrderAmount");
  return {
    from,
    to,
    totals: {
      createdOrderCount: sum("createdOrderCount"),
      createdOrderAmount: sum("createdOrderAmount"),
      paymentCount: sum("paymentCount"),
      paymentAmount: sum("paymentAmount"),
      completedOrderCount,
      completedOrderAmount,
      closedOrderCount: sum("closedOrderCount"),
      afterSaleCount: sum("afterSaleCount"),
      afterSaleAmount: sum("afterSaleAmount"),
      refundCount: sum("refundCount"),
      refundAmount: sum("refundAmount"),
      uniqueCustomers: daily.length === 0 ? 0 : 9,
    },
    daily,
    topProducts: completedOrderCount === 0 || productLimit < 1
      ? []
      : [{
          productId: analyticsProductId,
          productTitle: "青荷帆布通勤袋",
          completedOrderCount,
          unitsSold: completedOrderCount + 2,
          netRevenue: completedOrderAmount,
          revenueCoveredOrderCount: completedOrderCount,
        }].slice(0, productLimit),
    freshness: {
      sourceEventCount: daily.length * 17,
      lastConsumedAt: daily.length === 0
        ? null
        : "2026-08-03T08:02:00Z",
      generatedAt: "2026-08-03T08:02:05Z",
    },
  };
}
const alternateCustomer = {
  id: "2079000000000002999",
  email: "reader-two@example.com",
  displayName: "Second Reader",
  status: "ACTIVE",
  roles: ["CUSTOMER"],
};
const fixturePasswords = new Map([
  [customer.email, "ReaderPass123"],
  [alternateCustomer.email, "ReaderPass123"],
  [admin.email, "AdminPass123"],
]);
const addresses = [{
  id: "2079000000000000888",
  recipientName: "Test Customer",
  phone: "+86 13800000000",
  province: "浙江省",
  provinceCode: "330000",
  city: "杭州市",
  cityCode: "330100",
  district: "西湖区",
  districtCode: "330106",
  detailAddress: "文三路 1 号",
  postalCode: "310000",
  defaultAddress: true,
  version: 0,
  createdAt: now,
  updatedAt: now,
}];
const alternateAddresses = [{
  id: "2079000000000002888",
  recipientName: "Second Customer",
  phone: "+86 13900000000",
  province: "江苏省",
  provinceCode: "320000",
  city: "南京市",
  cityCode: "320100",
  district: "鼓楼区",
  districtCode: "320106",
  detailAddress: "中山路 2 号",
  postalCode: "210000",
  defaultAddress: true,
  version: 0,
  createdAt: now,
  updatedAt: now,
}];
const seededAddresses = structuredClone(addresses);
const seededAlternateAddresses = structuredClone(alternateAddresses);
const cartItems = [{
  id: "2079000000000000777",
  productId: "2079000000000000001",
  skuId: "2079000000000000011",
  productTitle: "帆布通勤袋",
  skuName: "自然色 / 中号",
  specJson: "{}",
  unitPrice: "189.00",
  quantity: 2,
  selected: true,
}];
const alternateCartItems = [{
  id: "2079000000000002777",
  productId: "2079000000000002001",
  skuId: "2079000000000002011",
  productTitle: "青灰随行本",
  skuName: "远天蓝 / A5",
  specJson: "{\"颜色\":\"远天蓝\",\"尺寸\":\"A5\"}",
  unitPrice: "89.00",
  quantity: 1,
  selected: true,
}];
const seededCartItems = structuredClone(cartItems);
const seededAlternateCartItems = structuredClone(alternateCartItems);
const cartMergeRequests = new Map();
let nextGeneratedCartItemId = 2079000000000002778n;
const benefit = {
  benefitNo: "BEN-001",
  userId: customer.id,
  ruleCode: "COUPON-10",
  benefitType: "COUPON",
  thresholdAmount: "100.00",
  discountAmount: "10.00",
  status: "AVAILABLE",
  lockedOrderNo: null,
  redeemedOrderNo: null,
  validFrom: "2026-07-19T00:00:00Z",
  validUntil: "2026-07-30T00:00:00Z",
  regions: [],
};
const afterSale = {
  afterSaleNo: "AS2079000000000003001",
  orderNo: "ORD2079000000000003002",
  userId: customer.id,
  afterSaleType: "RETURN_REFUND",
  status: "WAIT_RETURN",
  reason: "商品到货后存在明确破损",
  reviewReason: "符合整单退货退款条件",
  refundAmount: "378.00",
  returnReceiptNo: "RET2079000000000003003",
  refundNo: "REF2079000000000003004",
  items: [{
    lineNo: 1,
    skuId: cartItems[0].skuId,
    productTitle: cartItems[0].productTitle,
    skuName: cartItems[0].skuName,
    quantity: 2,
    lineAmount: "378.00",
    discountAmount: "0.00",
    refundableAmount: "378.00",
  }],
  version: 1,
  createdAt: now,
  updatedAt: now,
  approvedAt: now,
  completedAt: null,
};
const returnReceipt = {
  returnReceiptNo: afterSale.returnReceiptNo,
  afterSaleNo: afterSale.afterSaleNo,
  orderNo: afterSale.orderNo,
  userId: customer.id,
  warehouseId: "2079000000000003999",
  reservationNo: "RES2079000000000003005",
  status: "WAIT_SHIPMENT",
  refundAmount: afterSale.refundAmount,
  carrier: null,
  trackingNo: null,
  inspectionRemark: null,
  items: [{
    lineNo: 1,
    skuId: cartItems[0].skuId,
    quantity: 2,
    refundableAmount: "378.00",
  }],
  version: 0,
  createdAt: now,
  updatedAt: now,
  shippedAt: null,
  receivedAt: null,
  inspectedAt: null,
};
const refund = {
  refundNo: afterSale.refundNo,
  afterSaleNo: afterSale.afterSaleNo,
  orderNo: afterSale.orderNo,
  paymentNo: "PAY2079000000000003006",
  userId: customer.id,
  channel: "MOCK",
  status: "PROCESSING",
  amount: afterSale.refundAmount,
  channelRefundNo: null,
  requestStatus: "PENDING",
  requestAttempts: 0,
  nextRequestAt: null,
  requestSentAt: null,
  createdAt: now,
  updatedAt: now,
  refundedAt: null,
};
const seededAfterSale = structuredClone(afterSale);
const seededReturnReceipt = structuredClone(returnReceipt);
const seededRefund = structuredClone(refund);

function resetAfterSaleFixture() {
  Object.assign(afterSale, structuredClone(seededAfterSale));
  Object.assign(returnReceipt, structuredClone(seededReturnReceipt));
  Object.assign(refund, structuredClone(seededRefund));
}

const adminAfterSale = {
  afterSaleNo: "AS2079000000000003101",
  orderNo: "ORD2079000000000003102",
  userId: customer.id,
  afterSaleType: "WHOLE_RETURN_REFUND",
  status: "APPLIED",
  reason: "整单商品存在明确破损",
  reviewReason: null,
  refundAmount: "378.00",
  returnReceiptNo: null,
  refundNo: null,
  items: [{
    lineNo: 1,
    skuId: cartItems[0].skuId,
    productTitle: cartItems[0].productTitle,
    skuName: cartItems[0].skuName,
    quantity: 2,
    lineAmount: "398.00",
    discountAmount: "20.00",
    refundableAmount: "378.00",
  }],
  version: 0,
  createdAt: now,
  updatedAt: now,
  approvedAt: null,
  completedAt: null,
};
const seededAdminAfterSale = structuredClone(adminAfterSale);
const adminAfterSaleReviewCommands = [];
let adminAfterSaleFixtureMode = "normal";

function resetAdminAfterSaleFixture(mode = "normal") {
  adminAfterSaleFixtureMode = [
    "normal",
    "commit-lost",
    "retry-required",
  ].includes(mode)
    ? mode
    : "normal";
  Object.assign(adminAfterSale, structuredClone(seededAdminAfterSale));
  adminAfterSaleReviewCommands.length = 0;
}

function applyAdminAfterSaleReview(input) {
  const approved = input.approved === true;
  const target = approved ? "WAIT_RETURN" : "REJECTED";
  if (adminAfterSale.status !== "APPLIED") {
    return adminAfterSale.status === target;
  }
  adminAfterSale.status = target;
  adminAfterSale.reviewReason = String(input.reason ?? "");
  adminAfterSale.approvedAt = approved ? now : null;
  adminAfterSale.updatedAt = now;
  adminAfterSale.version += 1;
  return true;
}

const fulfillment = {
  fulfillmentNo: "FUL2079000000000004001",
  orderNo: "ORD2079000000000004002",
  userId: customer.id,
  deliveryAddress: {
    sourceAddressId: addresses[0].id,
    recipientName: addresses[0].recipientName,
    phone: addresses[0].phone,
    province: addresses[0].province,
    provinceCode: addresses[0].provinceCode,
    city: addresses[0].city,
    cityCode: addresses[0].cityCode,
    district: addresses[0].district,
    districtCode: addresses[0].districtCode,
    detailAddress: addresses[0].detailAddress,
    postalCode: addresses[0].postalCode,
  },
  status: "CREATED",
  carrier: null,
  trackingNo: null,
  history: [],
  traces: [],
  version: 0,
  createdAt: now,
  updatedAt: now,
  pickedAt: null,
  packedAt: null,
  shippedAt: null,
  signedAt: null,
};
const seededFulfillment = structuredClone(fulfillment);
const fulfillmentCommands = [];
let fulfillmentFixtureMode = "trace-retry";

function resetFulfillmentFixture(mode = "trace-retry") {
  fulfillmentFixtureMode = mode === "resolve-retry"
    ? "resolve-retry"
    : "trace-retry";
  Object.assign(fulfillment, structuredClone(seededFulfillment));
  fulfillmentCommands.splice(0, fulfillmentCommands.length);
  if (fulfillmentFixtureMode === "resolve-retry") {
    fulfillment.status = "EXCEPTION";
    fulfillment.version = 2;
    fulfillment.pickedAt = now;
    fulfillment.history = [{
      fromStatus: "CREATED",
      toStatus: "PICKING",
      command: "START_PICKING",
      reason: null,
      operatorType: "WAREHOUSE",
      operatorId: admin.id,
      createdAt: now,
    }, {
      fromStatus: "PICKING",
      toStatus: "EXCEPTION",
      command: "MARK_EXCEPTION",
      reason: "包裹标签需要人工复核",
      operatorType: "WAREHOUSE",
      operatorId: admin.id,
      createdAt: now,
    }];
    return;
  }
  fulfillment.status = "SHIPPED";
  fulfillment.carrier = "PLAIN_EXPRESS";
  fulfillment.trackingNo = "TRACK-QH-20260803";
  fulfillment.version = 3;
  fulfillment.pickedAt = now;
  fulfillment.packedAt = now;
  fulfillment.shippedAt = now;
  fulfillment.history = [{
    fromStatus: "CREATED",
    toStatus: "PICKING",
    command: "START_PICKING",
    reason: null,
    operatorType: "WAREHOUSE",
    operatorId: admin.id,
    createdAt: now,
  }, {
    fromStatus: "PICKING",
    toStatus: "PACKED",
    command: "MARK_PACKED",
    reason: null,
    operatorType: "WAREHOUSE",
    operatorId: admin.id,
    createdAt: now,
  }, {
    fromStatus: "PACKED",
    toStatus: "SHIPPED",
    command: "SHIP",
    reason: null,
    operatorType: "WAREHOUSE",
    operatorId: admin.id,
    createdAt: now,
  }];
}

const seededInventoryWarehouse = {
  id: returnReceipt.warehouseId,
  code: "HZ_MAIN",
  name: "杭州主仓",
  status: "ACTIVE",
  version: 0,
};
const inventoryWarehouses = [structuredClone(seededInventoryWarehouse)];
const inventoryStock = {
  warehouseId: seededInventoryWarehouse.id,
  skuId: cartItems[0].skuId,
  onHand: 10,
  reserved: 2,
  available: 8,
  version: 3,
};
const inventoryCommands = [];
let inventoryFixtureMode = "adjustment-retry";

function resetInventoryFixture(mode = "adjustment-retry") {
  inventoryFixtureMode = mode === "warehouse-authority"
    ? "warehouse-authority"
    : "adjustment-retry";
  inventoryWarehouses.splice(
    0,
    inventoryWarehouses.length,
    structuredClone(seededInventoryWarehouse),
  );
  Object.assign(inventoryStock, {
    warehouseId: seededInventoryWarehouse.id,
    skuId: cartItems[0].skuId,
    onHand: 10,
    reserved: 2,
    available: 8,
    version: 3,
  });
  inventoryCommands.splice(0, inventoryCommands.length);
}
const seededMarketingRule = {
  ruleCode: "QINGHE-WELCOME",
  name: "青荷新客权益规则",
  benefitType: "COUPON",
  thresholdAmount: "100.00",
  discountAmount: "10.00",
  stackOrder: 10,
  validFrom: "2026-08-03T00:00:00.000Z",
  validUntil: "2026-09-03T00:00:00.000Z",
  status: "ACTIVE",
  regions: [],
  version: 0,
};
const marketingRules = [structuredClone(seededMarketingRule)];
const marketingBenefits = [];
const marketingCommands = [];
let marketingFixtureMode = "grant-retry";
let nextMarketingBenefitId = 2079000000000008101n;

function resetMarketingFixture(mode = "grant-retry") {
  marketingFixtureMode = mode === "rule-unknown"
    ? "rule-unknown"
    : "grant-retry";
  marketingRules.splice(
    0,
    marketingRules.length,
    structuredClone(seededMarketingRule),
  );
  marketingBenefits.splice(0, marketingBenefits.length);
  marketingCommands.splice(0, marketingCommands.length);
  nextMarketingBenefitId = 2079000000000008101n;
}
const nearbyShipmentPosition = {
  fulfillmentNo: fulfillment.fulfillmentNo,
  orderNo: fulfillment.orderNo,
  userId: fulfillment.userId,
  status: "IN_TRANSIT",
  nodeType: "TRANSIT",
  locationName: "杭州市",
  longitude: "120.155100",
  latitude: "30.274100",
  distanceMeters: "0.00",
  occurredAt: now,
};
const reviewProduct = {
  id: cartItems[0].productId,
  title: cartItems[0].productTitle,
  subtitle: "轻量、耐用，保留材料本来的质感",
  description: "适合通勤与短途使用的克制日常用品。",
  status: "ACTIVE",
  version: 1,
  category: {
    id: "2079000000000000101",
    parentId: null,
    name: "随身用品",
    slug: "carry",
    sortOrder: 1,
  },
  brand: {
    id: "2079000000000000201",
    name: "素简记",
    slug: "plain-journal",
  },
  skus: [{
    id: cartItems[0].skuId,
    skuCode: "BAG-NATURAL-M",
    name: cartItems[0].skuName,
    specJson: "{\"颜色\":\"自然色\",\"尺寸\":\"中号\"}",
    salePrice: "189.00",
    marketPrice: "219.00",
    status: "ACTIVE",
    version: 0,
  }],
  media: [{
    id: "2079000000000000301",
    skuId: null,
    objectKey: "demo/catalog/canvas-commuter-tote.png",
    mimeType: "image/png",
    sizeBytes: 1999183,
    sortOrder: 0,
    url: "/images/catalog/canvas-commuter-tote.png",
  }],
};
const notebookProduct = {
  id: alternateCartItems[0].productId,
  title: alternateCartItems[0].productTitle,
  subtitle: "布纹封面与平摊装订，留给移动中的片刻记录",
  description: "一册强调纸张、装订和随身尺寸的 A5 日常笔记本。",
  status: "ACTIVE",
  version: 1,
  category: {
    id: "2079000000000002101",
    parentId: null,
    name: "书写纸品",
    slug: "writing",
    sortOrder: 2,
  },
  brand: reviewProduct.brand,
  skus: [{
    id: alternateCartItems[0].skuId,
    skuCode: "NOTE-MIST-A5",
    name: alternateCartItems[0].skuName,
    specJson: alternateCartItems[0].specJson,
    salePrice: alternateCartItems[0].unitPrice,
    marketPrice: "109.00",
    status: "ACTIVE",
    version: 0,
  }],
  media: [{
    id: "2079000000000002301",
    skuId: null,
    objectKey: "demo/catalog/mist-blue-notebook.png",
    mimeType: "image/png",
    sizeBytes: 2385220,
    sortOrder: 0,
    url: "/images/catalog/mist-blue-notebook.png",
  }],
};
const catalogProducts = [reviewProduct, notebookProduct];
const reviewOrder = {
  orderNo: "ORD2079000000000007001",
  status: "COMPLETED",
  totalAmount: "189.00",
  priceSnapshot: {
    originalAmount: "189.00",
    couponDiscount: "0.00",
    redPacketDiscount: "0.00",
    subsidyDiscount: "0.00",
    discountAmount: "0.00",
    payableAmount: "189.00",
    pricingVersion: 1,
    marketingLockNo: "MKT-REVIEW-001",
  },
  paymentDeadline: "2026-07-23T00:15:00Z",
  closeReason: null,
  deliveryAddress: {
    sourceAddressId: addresses[0].id,
    recipientName: addresses[0].recipientName,
    phone: addresses[0].phone,
    province: addresses[0].province,
    provinceCode: addresses[0].provinceCode,
    city: addresses[0].city,
    cityCode: addresses[0].cityCode,
    district: addresses[0].district,
    districtCode: addresses[0].districtCode,
    detailAddress: addresses[0].detailAddress,
    postalCode: addresses[0].postalCode,
  },
  items: [{
    lineNo: 1,
    productId: reviewProduct.id,
    skuId: reviewProduct.skus[0].id,
    productTitle: reviewProduct.title,
    skuCode: reviewProduct.skus[0].skuCode,
    skuName: reviewProduct.skus[0].name,
    specJson: reviewProduct.skus[0].specJson,
    imageObjectKey: null,
    unitPrice: "189.00",
    quantity: 1,
    lineAmount: "189.00",
    discountAmount: "0.00",
    payableAmount: "189.00",
  }],
  version: 4,
  createdAt: "2026-07-23T00:00:00Z",
  updatedAt: "2026-07-23T03:00:00Z",
};
const paymentOrder = {
  ...reviewOrder,
  orderNo: "ORD2079000000000008001",
  status: "PENDING_PAYMENT",
  totalAmount: "398.00",
  paymentDeadline: "2026-07-30T12:15:00Z",
  version: 1,
  createdAt: "2026-07-30T12:00:00Z",
  updatedAt: "2026-07-30T12:00:01Z",
  items: [{
    ...reviewOrder.items[0],
    quantity: 2,
    lineAmount: "398.00",
    payableAmount: "398.00",
  }],
};
const paymentExceptionOrder = {
  ...paymentOrder,
  orderNo: "ORD2079000000000009001",
  status: "PAYMENT_EXCEPTION",
  closeReason: "LATE_PAYMENT_DETECTED",
  version: 5,
  createdAt: "2026-07-31T12:00:00Z",
  updatedAt: "2026-07-31T12:20:00Z",
};
const paymentExceptionPayment = {
  paymentNo: "PAY2079000000000009002",
  orderNo: paymentExceptionOrder.orderNo,
  channel: "MOCK",
  status: "SUCCESS",
  amount: paymentExceptionOrder.totalAmount,
  channelTransactionNo: "MOCK-LATE-TXN-9002",
  paidAt: "2026-07-31T12:19:30Z",
  createdAt: "2026-07-31T12:05:00Z",
  updatedAt: "2026-07-31T12:19:30Z",
};
let paymentOrderPayment = null;
let paymentOrderIdempotencyKey = null;
const reviewFulfillment = {
  fulfillmentNo: "FUL2079000000000007002",
  orderNo: reviewOrder.orderNo,
  userId: customer.id,
  deliveryAddress: reviewOrder.deliveryAddress,
  status: "SIGNED",
  carrier: "MOCK_EXPRESS",
  trackingNo: "REVIEW-TRACK-001",
  history: [{
    fromStatus: "SHIPPED",
    toStatus: "SIGNED",
    command: "CONFIRM_RECEIPT",
    reason: null,
    operatorType: "CUSTOMER",
    operatorId: customer.id,
    createdAt: "2026-07-23T03:00:00Z",
  }],
  traces: [{
    externalEventId: "review-signed-001",
    nodeType: "SIGNED",
    description: "顾客已确认收货",
    locationName: "杭州市",
    longitude: "120.155100",
    latitude: "30.274100",
    occurredAt: "2026-07-23T03:00:00Z",
  }],
  version: 4,
  createdAt: "2026-07-23T00:20:00Z",
  updatedAt: "2026-07-23T03:00:00Z",
  pickedAt: "2026-07-23T00:30:00Z",
  packedAt: "2026-07-23T00:40:00Z",
  shippedAt: "2026-07-23T01:00:00Z",
  signedAt: "2026-07-23T03:00:00Z",
};
const reviewEligibility = {
  id: "2079000000000007101",
  orderNo: reviewOrder.orderNo,
  lineNo: 1,
  productId: reviewProduct.id,
  skuId: reviewProduct.skus[0].id,
  productTitle: reviewProduct.title,
  skuCode: reviewProduct.skus[0].skuCode,
  skuName: reviewProduct.skus[0].name,
  specJson: reviewProduct.skus[0].specJson,
  imageObjectKey: null,
  quantity: 1,
  status: "ELIGIBLE",
  reviewId: null,
  completedAt: "2026-07-23T03:00:00Z",
};
const seededProductReview = {
  id: "2079000000000007201",
  productId: reviewProduct.id,
  skuId: reviewProduct.skus[0].id,
  skuName: reviewProduct.skus[0].name,
  specJson: reviewProduct.skus[0].specJson,
  rating: 2,
  content: "这条评价包含需要平台核对的错误信息。",
  anonymous: true,
  authorLabel: "Anonymous verified customer",
  status: "PUBLISHED",
  likeCount: 0,
  likedByViewer: false,
  reply: null,
  createdAt: "2026-07-23T04:00:00Z",
};
const productReviews = [structuredClone(seededProductReview)];
const reviewOwners = new Map([
  [productReviews[0].id, "2079000000000000881"],
]);
const reviewIdempotency = new Map();
const reviewReports = [];
let nextReviewId = 2079000000000007202n;
let nextReviewReportId = 2079000000000007301n;
const adminReviewReplyCommands = [];
const adminReviewModerationCommands = [];
const adminReviewReplyResults = new Map();
const adminReviewModerationResults = new Map();
let adminReviewFixtureMode = "normal";
const chatConversations = [{
  id: "2079000000000005001",
  conversationNo: "CHAT-20260724-0001",
  customerId: customer.id,
  assignedAgentId: null,
  subject: "帆布通勤袋保养方式",
  contextType: null,
  contextId: null,
  status: "OPEN",
  lastMessageSequence: 1,
  unreadCount: 1,
  version: 0,
  createdAt: now,
  updatedAt: now,
}];
const chatMessages = new Map([
  [chatConversations[0].id, [{
    id: "2079000000000005101",
    conversationId: chatConversations[0].id,
    senderId: customer.id,
    clientMessageId: "chat:message:browser-fixture",
    sequence: 1,
    messageType: "TEXT",
    content: "请问帆布通勤袋可以水洗吗？",
    attachments: [],
    status: "STORED",
    createdAt: now,
  }]],
]);
let nextChatConversationId = 2079000000000005002n;
let nextChatMessageId = 2079000000000005102n;
const seededChatConversations = structuredClone(chatConversations);
const seededChatMessages = structuredClone([...chatMessages.entries()]);
const adminChatClaimCommands = [];
const adminChatSendCommands = [];
const adminChatCloseCommands = [];
let adminChatFixtureMode = "normal";
let adminChatPreClaimMessageReads = 0;

function resetAdminChatFixture(mode = "normal") {
  adminChatFixtureMode = mode === "recovery-chain"
    ? "recovery-chain"
    : "normal";
  chatConversations.splice(
    0,
    chatConversations.length,
    ...structuredClone(seededChatConversations),
  );
  chatMessages.clear();
  for (const [conversationId, messages] of structuredClone(seededChatMessages)) {
    chatMessages.set(conversationId, messages);
  }
  nextChatConversationId = 2079000000000005002n;
  nextChatMessageId = 2079000000000005102n;
  adminChatClaimCommands.length = 0;
  adminChatSendCommands.length = 0;
  adminChatCloseCommands.length = 0;
  adminChatPreClaimMessageReads = 0;
}
const governanceRefundNo = "RF-DEMO-NEEDS-ATTENTION";
const governancePaymentNo = "PAY-DEMO-EXCEPTION";
const governanceRefund = {
  refundNo: governanceRefundNo,
  afterSaleNo: "AS-DEMO-REFUND",
  orderNo: "ORD-DEMO-REFUND",
  paymentNo: "PAY-DEMO-REFUND",
  userId: customer.id,
  channel: "MOCK",
  status: "PROCESSING",
  amount: "398.00",
  channelRefundNo: null,
  requestStatus: "NEEDS_ATTENTION",
  requestAttempts: 5,
  nextRequestAt: null,
  requestSentAt: now,
  createdAt: now,
  updatedAt: now,
  refundedAt: null,
};
const governanceExceptionRefund = {
  refundNo: "RF-DEMO-EXCEPTION",
  afterSaleNo: "PAYMENT_EXCEPTION:ORD-DEMO-EXCEPTION",
  orderNo: "ORD-DEMO-EXCEPTION",
  paymentNo: governancePaymentNo,
  userId: customer.id,
  channel: "MOCK",
  status: "PROCESSING",
  amount: "268.00",
  channelRefundNo: null,
  requestStatus: "PENDING",
  requestAttempts: 0,
  nextRequestAt: now,
  requestSentAt: null,
  createdAt: now,
  updatedAt: now,
  refundedAt: null,
};
const governanceRefundCommands = [];
const governanceExceptionCommands = [];
const governanceRefundAudits = [];
const governanceExceptionAudits = [];
let governanceFixtureMode = "audit-confirmed";

function envelope(data, code = "OK", message = "success") {
  return JSON.stringify({ code, message, data, timestamp: now });
}

function respond(response, status, data, code = "OK", message = "success") {
  response.writeHead(status, {
    "Access-Control-Allow-Headers": "Authorization, Content-Type, Idempotency-Key",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Origin": "*",
    "Content-Type": "application/json; charset=utf-8",
  });
  response.end(envelope(data, code, message));
}

function positiveQueryInteger(url, name, fallback) {
  const raw = url.searchParams.get(name);
  if (!raw || !/^\d+$/u.test(raw)) {
    return fallback;
  }
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function paginate(values, page, size) {
  const offset = (page - 1) * size;
  return values.slice(offset, offset + size);
}

async function body(request) {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  return chunks.length > 0
    ? JSON.parse(Buffer.concat(chunks).toString("utf8"))
    : {};
}

let nextGeneratedAddressId = 2079000000000000889n;

function nextAddressId() {
  const result = String(nextGeneratedAddressId);
  nextGeneratedAddressId += 1n;
  return result;
}

function identityActor(request) {
  const authorization = String(request.headers.authorization ?? "");
  if (authorization.includes("admin")) {
    return admin;
  }
  return authorization.includes("customer-two")
    ? alternateCustomer
    : customer;
}

function addressBook(request) {
  return identityActor(request).id === alternateCustomer.id
    ? alternateAddresses
    : addresses;
}

function cartBook(request) {
  const actor = identityActor(request);
  if (actor.id === alternateCustomer.id) {
    return alternateCartItems;
  }
  if (actor.id === customer.id) {
    return cartItems;
  }
  return [];
}

function nextCartItemId() {
  const result = String(nextGeneratedCartItemId);
  nextGeneratedCartItemId += 1n;
  return result;
}

function cartSnapshot(productId, skuId, existing = null) {
  if (productId === cartItems[0].productId && skuId === cartItems[0].skuId) {
    return {
      productTitle: cartItems[0].productTitle,
      skuName: cartItems[0].skuName,
      specJson: cartItems[0].specJson,
      unitPrice: cartItems[0].unitPrice,
    };
  }
  if (existing) {
    return {
      productTitle: existing.productTitle,
      skuName: existing.skuName,
      specJson: existing.specJson,
      unitPrice: existing.unitPrice,
    };
  }
  return null;
}

function chatActor(request) {
  return String(request.headers.authorization ?? "").includes("admin")
    ? admin
    : customer;
}

function reviewSummary(productId = reviewProduct.id) {
  const published = productReviews.filter((review) =>
    review.productId === productId && review.status === "PUBLISHED");
  const ratingCount = (rating) => published.filter((review) => review.rating === rating).length;
  const ratingSum = published.reduce((sum, review) => sum + review.rating, 0);
  return {
    productId,
    reviewCount: published.length,
    averageRating: published.length === 0
      ? 0
      : Number((ratingSum / published.length).toFixed(1)),
    rating1Count: ratingCount(1),
    rating2Count: ratingCount(2),
    rating3Count: ratingCount(3),
    rating4Count: ratingCount(4),
    rating5Count: ratingCount(5),
  };
}

function resetReviewFixture() {
  productReviews.splice(0, productReviews.length, structuredClone(seededProductReview));
  reviewOwners.clear();
  reviewOwners.set(seededProductReview.id, "2079000000000000881");
  reviewIdempotency.clear();
  reviewReports.splice(0, reviewReports.length);
  adminReviewReplyCommands.length = 0;
  adminReviewModerationCommands.length = 0;
  adminReviewReplyResults.clear();
  adminReviewModerationResults.clear();
  adminReviewFixtureMode = "normal";
  reviewEligibility.status = "ELIGIBLE";
  reviewEligibility.reviewId = null;
  nextReviewId = 2079000000000007202n;
  nextReviewReportId = 2079000000000007301n;
}

function resetAdminReviewFixture(mode = "normal") {
  resetReviewFixture();
  adminReviewFixtureMode = [
    "reply-commit-lost",
    "moderation-commit-lost",
  ].includes(mode)
    ? mode
    : "normal";
  reviewReports.push({
    id: String(nextReviewReportId++),
    reviewId: seededProductReview.id,
    productId: seededProductReview.productId,
    reporterUserId: customer.id,
    rating: seededProductReview.rating,
    reviewContent: seededProductReview.content,
    reasonCode: "FALSE_INFORMATION",
    detail: "该描述与订单商品规格不一致，请平台核对。",
    status: "OPEN",
    resolution: null,
    createdAt: now,
    resolvedAt: null,
  });
}

function resetCartFixture() {
  cartItems.splice(
    0,
    cartItems.length,
    ...structuredClone(seededCartItems),
  );
  alternateCartItems.splice(
    0,
    alternateCartItems.length,
    ...structuredClone(seededAlternateCartItems),
  );
  cartMergeRequests.clear();
  nextGeneratedCartItemId = 2079000000000002778n;
}

function resetAddressFixture() {
  addresses.splice(
    0,
    addresses.length,
    ...structuredClone(seededAddresses),
  );
  alternateAddresses.splice(
    0,
    alternateAddresses.length,
    ...structuredClone(seededAlternateAddresses),
  );
  nextGeneratedAddressId = 2079000000000000889n;
}

function resetGovernanceFixture(mode = "audit-confirmed") {
  governanceFixtureMode = mode === "retry-required"
    ? "retry-required"
    : "audit-confirmed";
  governanceRefundCommands.splice(0, governanceRefundCommands.length);
  governanceExceptionCommands.splice(0, governanceExceptionCommands.length);
  governanceRefundAudits.splice(0, governanceRefundAudits.length);
  governanceExceptionAudits.splice(0, governanceExceptionAudits.length);
  governanceRefund.requestStatus = "NEEDS_ATTENTION";
  governanceRefund.requestAttempts = 5;
  governanceRefund.nextRequestAt = null;
  governanceRefund.updatedAt = now;
}

function governanceRefundAudit(command) {
  return {
    commandId: command.commandId,
    refundNo: command.referenceNo,
    operatorId: admin.id,
    reason: command.reason,
    outcome: "ACCEPTED",
    errorCode: null,
    beforeRefundStatus: "PROCESSING",
    beforeRequestStatus: "NEEDS_ATTENTION",
    beforeRequestAttempts: 5,
    beforeLastError: "channel unavailable",
    afterRefundStatus: "PROCESSING",
    afterRequestStatus: "PENDING",
    afterRequestAttempts: 0,
    createdAt: now,
  };
}

function governanceExceptionAudit(command) {
  return {
    commandId: command.commandId,
    paymentNo: command.referenceNo,
    orderNo: governanceExceptionRefund.orderNo,
    refundNo: governanceExceptionRefund.refundNo,
    operatorId: admin.id,
    reason: command.reason,
    outcome: "ACCEPTED",
    errorCode: null,
    createdAt: now,
  };
}

function chatConversation(conversationId) {
  return chatConversations.find((conversation) =>
    conversation.id === conversationId);
}

function chatMessagePage(conversationId, beforeSequence, size) {
  const messages = [...(chatMessages.get(conversationId) ?? [])]
    .filter((message) =>
      beforeSequence === null || message.sequence < beforeSequence)
    .sort((left, right) => right.sequence - left.sequence);
  const selected = messages.slice(0, size);
  const hasMore = messages.length > selected.length;
  return {
    items: selected.sort((left, right) => left.sequence - right.sequence),
    nextBeforeSequence: hasMore && selected.length > 0
      ? selected[0].sequence
      : null,
    hasMore,
  };
}

createServer(async (request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  const method = request.method ?? "GET";

  if (method === "OPTIONS") {
    respond(response, 204, null);
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/reviews/reset"
  ) {
    resetReviewFixture();
    respond(response, 200, { reset: true });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/admin-review/reset"
  ) {
    const input = await body(request);
    resetAdminReviewFixture(String(input.mode ?? "normal"));
    respond(response, 200, {
      reset: true,
      mode: adminReviewFixtureMode,
      reportId: reviewReports[0]?.id ?? null,
      reviewId: seededProductReview.id,
      productId: seededProductReview.productId,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/__test__/fixtures/admin-review/diagnostics"
  ) {
    respond(response, 200, {
      mode: adminReviewFixtureMode,
      replyCommands: adminReviewReplyCommands,
      moderationCommands: adminReviewModerationCommands,
      reports: reviewReports,
      reviews: productReviews,
      summary: reviewSummary(seededProductReview.productId),
    });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/admin-chat/reset"
  ) {
    const input = await body(request);
    resetAdminChatFixture(String(input.mode ?? "normal"));
    respond(response, 200, {
      reset: true,
      mode: adminChatFixtureMode,
      conversationId: chatConversations[0]?.id ?? null,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/__test__/fixtures/admin-chat/diagnostics"
  ) {
    respond(response, 200, {
      mode: adminChatFixtureMode,
      claimCommands: adminChatClaimCommands,
      sendCommands: adminChatSendCommands,
      closeCommands: adminChatCloseCommands,
      preClaimMessageReads: adminChatPreClaimMessageReads,
      conversations: chatConversations,
      messages: [...chatMessages.entries()].map(([conversationId, messages]) => ({
        conversationId,
        messages,
      })),
    });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/cart/reset"
  ) {
    resetCartFixture();
    respond(response, 200, { reset: true });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/addresses/reset"
  ) {
    resetAddressFixture();
    respond(response, 200, { reset: true });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/payment/reset"
  ) {
    paymentOrderPayment = null;
    paymentOrderIdempotencyKey = null;
    respond(response, 200, { reset: true });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/after-sale/reset"
  ) {
    resetAfterSaleFixture();
    respond(response, 200, { reset: true });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/admin-after-sale/reset"
  ) {
    const input = await body(request);
    resetAdminAfterSaleFixture(String(input.mode ?? "normal"));
    respond(response, 200, {
      reset: true,
      mode: adminAfterSaleFixtureMode,
      afterSaleNo: adminAfterSale.afterSaleNo,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/__test__/fixtures/admin-after-sale/diagnostics"
  ) {
    respond(response, 200, {
      mode: adminAfterSaleFixtureMode,
      commands: adminAfterSaleReviewCommands,
      afterSale: adminAfterSale,
    });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/governance/reset"
  ) {
    const input = await body(request);
    resetGovernanceFixture(String(input.mode ?? "audit-confirmed"));
    respond(response, 200, {
      reset: true,
      mode: governanceFixtureMode,
      refundNo: governanceRefundNo,
      paymentNo: governancePaymentNo,
    });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/fulfillment/reset"
  ) {
    const input = await body(request);
    resetFulfillmentFixture(String(input.mode ?? "trace-retry"));
    respond(response, 200, {
      reset: true,
      mode: fulfillmentFixtureMode,
      fulfillmentNo: fulfillment.fulfillmentNo,
    });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/inventory/reset"
  ) {
    const input = await body(request);
    resetInventoryFixture(String(input.mode ?? "adjustment-retry"));
    respond(response, 200, {
      reset: true,
      mode: inventoryFixtureMode,
      warehouseId: inventoryStock.warehouseId,
      skuId: inventoryStock.skuId,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/__test__/fixtures/inventory/diagnostics"
  ) {
    respond(response, 200, {
      mode: inventoryFixtureMode,
      commands: inventoryCommands,
      warehouses: inventoryWarehouses,
      stock: inventoryStock,
    });
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/__test__/fixtures/marketing/reset"
  ) {
    const input = await body(request);
    resetMarketingFixture(String(input.mode ?? "grant-retry"));
    respond(response, 200, {
      reset: true,
      mode: marketingFixtureMode,
      userId: customer.id,
      ruleCode: seededMarketingRule.ruleCode,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/__test__/fixtures/marketing/diagnostics"
  ) {
    respond(response, 200, {
      mode: marketingFixtureMode,
      commands: marketingCommands,
      rules: marketingRules,
      benefits: marketingBenefits,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/__test__/fixtures/fulfillment/diagnostics"
  ) {
    respond(response, 200, {
      mode: fulfillmentFixtureMode,
      commands: fulfillmentCommands,
      fulfillment,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/__test__/fixtures/governance/diagnostics"
  ) {
    respond(response, 200, {
      mode: governanceFixtureMode,
      refundCommands: governanceRefundCommands,
      exceptionCommands: governanceExceptionCommands,
      refundAudits: governanceRefundAudits,
      exceptionAudits: governanceExceptionAudits,
    });
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/identity/auth/register") {
    await body(request);
    respond(response, 200, customer);
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/identity/auth/login") {
    const input = await body(request);
    const email = String(input.email ?? "").trim().toLowerCase();
    const password = String(input.password ?? "");
    if (fixturePasswords.get(email) !== password) {
      respond(
        response,
        401,
        null,
        "INVALID_CREDENTIALS",
        "The email or password is incorrect",
      );
      return;
    }
    const staff = email === admin.email;
    const alternate = email === alternateCustomer.email;
    respond(response, 200, {
      tokenType: "Bearer",
      accessToken: staff
        ? "browser-admin-access-token"
        : alternate
          ? "browser-customer-two-access-token"
          : "browser-customer-access-token",
      expiresIn: 900,
      refreshToken: staff
        ? "browser-admin-refresh-token"
        : alternate
          ? "browser-customer-two-refresh-token"
          : "browser-customer-refresh-token",
    });
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/identity/auth/refresh") {
    const input = await body(request);
    if (input.refreshToken === "expired-customer-refresh-token") {
      respond(
        response,
        401,
        null,
        "INVALID_REFRESH_TOKEN",
        "The refresh token is invalid or expired",
      );
      return;
    }
    const refreshToken = String(input.refreshToken ?? "");
    const staff = refreshToken.includes("admin");
    const alternate = refreshToken.includes("customer-two");
    respond(response, 200, {
      tokenType: "Bearer",
      accessToken: staff
        ? "browser-admin-access-token-rotated"
        : alternate
          ? "browser-customer-two-access-token-rotated"
          : "browser-customer-access-token-rotated",
      expiresIn: 900,
      refreshToken: staff
        ? "browser-admin-refresh-token-rotated"
        : alternate
          ? "browser-customer-two-refresh-token-rotated"
          : "browser-customer-refresh-token-rotated",
    });
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/identity/auth/logout") {
    respond(response, 200, null);
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/identity/me") {
    respond(response, 200, identityActor(request));
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/analytics/overview") {
    const actor = identityActor(request);
    if (!actor.roles.some((role) => role === "ADMIN" || role === "OPERATOR")) {
      respond(
        response,
        403,
        null,
        "FORBIDDEN",
        "analytics overview requires ADMIN or OPERATOR",
      );
      return;
    }
    const from = url.searchParams.get("from") ?? "2026-08-02";
    const to = url.searchParams.get("to") ?? "2026-08-03";
    const productLimit = Number(url.searchParams.get("productLimit") ?? 8);
    respond(response, 200, analyticsOverview(from, to, productLimit));
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/catalog/categories") {
    respond(response, 200, catalogProducts.map((product) => product.category));
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/catalog/products") {
    const page = positiveQueryInteger(url, "page", 1);
    const size = positiveQueryInteger(url, "size", 20);
    const categoryId = url.searchParams.get("categoryId");
    const keyword = (url.searchParams.get("keyword") ?? "").trim().toLowerCase();
    const selectedProducts = catalogProducts.filter((product) =>
      (!categoryId || product.category.id === categoryId)
      && (!keyword
        || product.title.toLowerCase().includes(keyword)
        || (product.subtitle ?? "").toLowerCase().includes(keyword)));
    const summaries = selectedProducts.map((product) => ({
      id: product.id,
      title: product.title,
      subtitle: product.subtitle,
      category: product.category,
      brand: product.brand,
      minimumPrice: product.skus[0].salePrice,
      coverUrl: product.media[0]?.url ?? null,
    }));
    respond(response, 200, {
      items: paginate(summaries, page, size),
      page,
      size,
      total: summaries.length,
    });
    return;
  }

  if (method === "GET" && url.pathname === "/api/v1/catalog/search/products") {
    const page = positiveQueryInteger(url, "page", 1);
    const size = positiveQueryInteger(url, "size", 20);
    const keyword = (url.searchParams.get("q") ?? "").trim().toLowerCase();
    const categoryId = url.searchParams.get("categoryId");
    const matches = catalogProducts
      .filter((product) =>
        (!categoryId || product.category.id === categoryId)
        && (
          product.title.toLowerCase().includes(keyword)
          || (product.subtitle ?? "").toLowerCase().includes(keyword)
        ))
      .map((product) => ({
        id: product.id,
        title: product.title,
        subtitle: product.subtitle,
        category: product.category,
        brand: product.brand,
        minimumPrice: product.skus[0].salePrice,
        coverUrl: product.media[0]?.url ?? null,
      }));
    respond(response, 200, {
      items: paginate(matches, page, size),
      page,
      size,
      matchedTotal: matches.length,
      source: "OPENSEARCH",
      degraded: false,
    });
    return;
  }
  const productDetailMatch = url.pathname.match(/^\/api\/v1\/catalog\/products\/([^/]+)$/u);
  if (method === "GET" && productDetailMatch) {
    const selectedProduct = catalogProducts.find((product) =>
      product.id === productDetailMatch[1]);
    if (!selectedProduct) {
      respond(response, 404, null);
      return;
    }
    respond(response, 200, selectedProduct);
    return;
  }
  const reviewSummaryMatch = url.pathname.match(
    /^\/api\/v1\/catalog\/products\/([^/]+)\/review-summary$/u,
  );
  if (method === "GET" && reviewSummaryMatch) {
    respond(response, 200, reviewSummary(reviewSummaryMatch[1]));
    return;
  }
  const productReviewsMatch = url.pathname.match(
    /^\/api\/v1\/catalog\/products\/([^/]+)\/reviews$/u,
  );
  if (method === "GET" && productReviewsMatch) {
    const values = productReviews
      .filter((review) =>
        review.productId === productReviewsMatch[1]
        && review.status === "PUBLISHED")
      .map((review) => ({ ...review }));
    respond(response, 200, {
      items: values,
      page: Number(url.searchParams.get("page") ?? 1),
      size: Number(url.searchParams.get("size") ?? 20),
      total: values.length,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/api/v1/catalog/review-eligibilities"
  ) {
    const selectedOrder = url.searchParams.get("orderNo");
    respond(
      response,
      200,
      !selectedOrder || selectedOrder === reviewEligibility.orderNo
        ? [reviewEligibility]
        : [],
    );
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/catalog/reviews") {
    const actor = chatActor(request);
    const key = String(request.headers["idempotency-key"] ?? "");
    const input = await body(request);
    if (actor.id !== customer.id || input.eligibilityId !== reviewEligibility.id) {
      respond(response, 404, null, "RESOURCE_NOT_FOUND", "Review eligibility not found");
      return;
    }
    const existing = reviewIdempotency.get(key);
    if (existing) {
      respond(response, 200, existing);
      return;
    }
    if (reviewEligibility.status === "REVIEWED") {
      respond(response, 409, null, "REVIEW_ALREADY_SUBMITTED", "Review already submitted");
      return;
    }
    const created = {
      id: String(nextReviewId++),
      productId: reviewProduct.id,
      skuId: reviewProduct.skus[0].id,
      skuName: reviewProduct.skus[0].name,
      specJson: reviewProduct.skus[0].specJson,
      rating: input.rating,
      content: input.content,
      anonymous: Boolean(input.anonymous),
      authorLabel: input.anonymous
        ? "Anonymous verified customer"
        : "Verified customer",
      status: "PUBLISHED",
      likeCount: 0,
      likedByViewer: false,
      reply: null,
      createdAt: now,
    };
    productReviews.unshift(created);
    reviewOwners.set(created.id, actor.id);
    reviewIdempotency.set(key, created);
    reviewEligibility.status = "REVIEWED";
    reviewEligibility.reviewId = created.id;
    respond(response, 200, created);
    return;
  }
  const reviewLikeMatch = /^\/api\/v1\/catalog\/reviews\/(\d+)\/likes$/.exec(
    url.pathname,
  );
  if (reviewLikeMatch?.[1] && (method === "POST" || method === "DELETE")) {
    const selected = productReviews.find((review) => review.id === reviewLikeMatch[1]);
    if (!selected || selected.status !== "PUBLISHED") {
      respond(response, 409, null, "REVIEW_NOT_PUBLISHED", "Review is not published");
      return;
    }
    const shouldLike = method === "POST";
    if (selected.likedByViewer !== shouldLike) {
      selected.likedByViewer = shouldLike;
      selected.likeCount += shouldLike ? 1 : -1;
    }
    respond(response, 200, selected);
    return;
  }
  const reviewReportMatch = /^\/api\/v1\/catalog\/reviews\/(\d+)\/reports$/.exec(
    url.pathname,
  );
  if (reviewReportMatch?.[1] && method === "POST") {
    const actor = chatActor(request);
    const selected = productReviews.find((review) => review.id === reviewReportMatch[1]);
    if (!selected || selected.status !== "PUBLISHED") {
      respond(response, 409, null, "REVIEW_NOT_PUBLISHED", "Review is not published");
      return;
    }
    if (reviewOwners.get(selected.id) === actor.id) {
      respond(response, 409, null, "REVIEW_ACTION_NOT_ALLOWED", "Self report is not allowed");
      return;
    }
    const existing = reviewReports.find((report) =>
      report.reviewId === selected.id && report.reporterUserId === actor.id);
    if (existing) {
      respond(response, 200, {
        id: existing.id,
        reviewId: existing.reviewId,
        status: existing.status,
        createdAt: existing.createdAt,
      });
      return;
    }
    const input = await body(request);
    const created = {
      id: String(nextReviewReportId++),
      reviewId: selected.id,
      productId: selected.productId,
      reporterUserId: actor.id,
      rating: selected.rating,
      reviewContent: selected.content,
      reasonCode: input.reasonCode,
      detail: input.detail ?? null,
      status: "OPEN",
      resolution: null,
      createdAt: now,
      resolvedAt: null,
    };
    reviewReports.push(created);
    respond(response, 200, {
      id: created.id,
      reviewId: created.reviewId,
      status: created.status,
      createdAt: created.createdAt,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/api/v1/catalog/admin/reviews/reports"
  ) {
    const status = url.searchParams.get("status");
    const values = reviewReports.filter((report) => !status || report.status === status);
    respond(response, 200, {
      items: values,
      page: Number(url.searchParams.get("page") ?? 1),
      size: Number(url.searchParams.get("size") ?? 20),
      total: values.length,
    });
    return;
  }
  const reviewReplyMatch = /^\/api\/v1\/catalog\/admin\/reviews\/(\d+)\/reply$/.exec(
    url.pathname,
  );
  if (reviewReplyMatch?.[1] && method === "POST") {
    const commandId = String(request.headers["idempotency-key"] ?? "");
    const input = await body(request);
    const previous = adminReviewReplyResults.get(commandId);
    const attempt = adminReviewReplyCommands.filter((command) =>
      command.commandId === commandId).length + 1;
    adminReviewReplyCommands.push({
      commandId,
      reviewId: reviewReplyMatch[1],
      content: input.content,
      attempt,
    });
    if (previous) {
      if (
        previous.reviewId !== reviewReplyMatch[1]
        || previous.content !== input.content
      ) {
        respond(
          response,
          409,
          null,
          "IDEMPOTENCY_CONFLICT",
          "Reply command payload conflict",
        );
        return;
      }
      respond(response, 200, previous.result);
      return;
    }
    const selected = productReviews.find((review) => review.id === reviewReplyMatch[1]);
    if (!selected || selected.status !== "PUBLISHED") {
      respond(response, 409, null, "REVIEW_NOT_PUBLISHED", "Review is not published");
      return;
    }
    if (selected.reply) {
      respond(
        response,
        409,
        null,
        "REVIEW_ACTION_NOT_ALLOWED",
        "Review already has a platform reply",
      );
      return;
    }
    selected.reply = {
      id: String(2079000000000007401n + BigInt(productReviews.indexOf(selected))),
      content: input.content,
      createdAt: now,
    };
    const result = structuredClone(selected);
    adminReviewReplyResults.set(commandId, {
      reviewId: reviewReplyMatch[1],
      content: input.content,
      result,
    });
    if (
      adminReviewFixtureMode === "reply-commit-lost"
      && attempt === 1
    ) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "Reply committed but response was lost",
      );
      return;
    }
    respond(response, 200, result);
    return;
  }
  const reviewResolveMatch =
    /^\/api\/v1\/catalog\/admin\/reviews\/reports\/(\d+)\/resolve$/.exec(
      url.pathname,
    );
  if (reviewResolveMatch?.[1] && method === "POST") {
    const input = await body(request);
    const commandId = String(input.commandId ?? "");
    const previous = adminReviewModerationResults.get(commandId);
    const attempt = adminReviewModerationCommands.filter((command) =>
      command.commandId === commandId).length + 1;
    adminReviewModerationCommands.push({
      commandId,
      reportId: reviewResolveMatch[1],
      resolution: input.resolution,
      reason: input.reason,
      attempt,
    });
    if (previous) {
      if (
        previous.reportId !== reviewResolveMatch[1]
        || previous.resolution !== input.resolution
        || previous.reason !== input.reason
      ) {
        respond(
          response,
          409,
          null,
          "IDEMPOTENCY_CONFLICT",
          "Moderation command payload conflict",
        );
        return;
      }
      respond(response, 200, previous.result);
      return;
    }
    const selected = reviewReports.find((report) => report.id === reviewResolveMatch[1]);
    if (!selected || selected.status !== "OPEN") {
      respond(response, 409, null, "REPORT_ALREADY_RESOLVED", "Report already resolved");
      return;
    }
    const review = productReviews.find((candidate) => candidate.id === selected.reviewId);
    const before = review?.status ?? "HIDDEN";
    if (review && input.resolution === "UPHELD") {
      review.status = "HIDDEN";
    }
    selected.status = "RESOLVED";
    selected.resolution = input.resolution;
    selected.resolvedAt = now;
    const result = {
      reportId: selected.id,
      reviewId: selected.reviewId,
      commandId,
      resolution: input.resolution,
      reviewStatusBefore: before,
      reviewStatusAfter: review?.status ?? before,
      resolvedAt: now,
    };
    adminReviewModerationResults.set(commandId, {
      reportId: reviewResolveMatch[1],
      resolution: input.resolution,
      reason: input.reason,
      result,
    });
    if (
      adminReviewFixtureMode === "moderation-commit-lost"
      && attempt === 1
    ) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "Moderation committed but response was lost",
      );
      return;
    }
    respond(response, 200, result);
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/api/v1/trade/orders/page"
  ) {
    const actor = identityActor(request);
    const items = actor.id === customer.id ? [paymentOrder, reviewOrder] : [];
    respond(response, 200, {
      items,
      page: 1,
      size: Number(url.searchParams.get("size") ?? 20),
      total: items.length,
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/trade/orders/${paymentOrder.orderNo}`
  ) {
    if (identityActor(request).id !== customer.id) {
      respond(response, 404, null, "ORDER_NOT_FOUND", "Order not found");
      return;
    }
    respond(response, 200, paymentOrder);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/trade/orders/${paymentExceptionOrder.orderNo}`
  ) {
    if (identityActor(request).id !== customer.id) {
      respond(response, 404, null, "ORDER_NOT_FOUND", "Order not found");
      return;
    }
    respond(response, 200, paymentExceptionOrder);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/payment/payments/by-order/${paymentExceptionOrder.orderNo}`
  ) {
    if (identityActor(request).id !== customer.id) {
      respond(response, 404, null, "RESOURCE_NOT_FOUND", "Payment not found");
      return;
    }
    respond(response, 200, paymentExceptionPayment);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/payment/payments/by-order/${paymentOrder.orderNo}`
  ) {
    if (!paymentOrderPayment || identityActor(request).id !== customer.id) {
      respond(response, 404, null, "RESOURCE_NOT_FOUND", "Payment not found");
      return;
    }
    respond(response, 200, paymentOrderPayment);
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/payment/payments") {
    if (identityActor(request).id !== customer.id) {
      respond(response, 403, null, "FORBIDDEN", "Customer owner required");
      return;
    }
    const input = await body(request);
    if (input.orderNo !== paymentOrder.orderNo) {
      respond(response, 404, null, "ORDER_NOT_FOUND", "Order not found");
      return;
    }
    paymentOrderIdempotencyKey = String(request.headers["idempotency-key"] ?? "");
    paymentOrderPayment ??= {
      paymentNo: "PAY2079000000000008002",
      orderNo: paymentOrder.orderNo,
      channel: "MOCK",
      status: "PROCESSING",
      amount: paymentOrder.totalAmount,
      channelTransactionNo: null,
      paidAt: null,
      createdAt: "2026-07-30T12:01:00Z",
      updatedAt: "2026-07-30T12:01:00Z",
    };
    respond(response, 200, paymentOrderPayment);
    return;
  }
  if (
    method === "GET"
    && url.pathname.startsWith("/api/v1/payment/payments/by-idempotency-key/")
  ) {
    const key = decodeURIComponent(
      url.pathname.slice("/api/v1/payment/payments/by-idempotency-key/".length),
    );
    if (
      !paymentOrderPayment
      || key !== paymentOrderIdempotencyKey
      || identityActor(request).id !== customer.id
    ) {
      respond(response, 404, null, "RESOURCE_NOT_FOUND", "Payment not found");
      return;
    }
    respond(response, 200, paymentOrderPayment);
    return;
  }
  if (
    method === "GET"
    && paymentOrderPayment
    && url.pathname === `/api/v1/payment/payments/${paymentOrderPayment.paymentNo}`
  ) {
    if (identityActor(request).id !== customer.id) {
      respond(response, 404, null, "RESOURCE_NOT_FOUND", "Payment not found");
      return;
    }
    respond(response, 200, paymentOrderPayment);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/trade/orders/${reviewOrder.orderNo}`
  ) {
    respond(response, 200, reviewOrder);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/payment/payments/by-order/${reviewOrder.orderNo}`
  ) {
    respond(response, 200, {
      paymentNo: "PAY2079000000000007003",
      orderNo: reviewOrder.orderNo,
      channel: "MOCK",
      status: "SUCCESS",
      amount: reviewOrder.totalAmount,
      channelTransactionNo: "MOCK-REVIEW-TXN",
      paidAt: "2026-07-23T00:10:00Z",
      createdAt: "2026-07-23T00:05:00Z",
      updatedAt: "2026-07-23T00:10:00Z",
    });
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/fulfillment/orders/${reviewOrder.orderNo}`
  ) {
    respond(response, 200, reviewFulfillment);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/fulfillment/orders/${reviewOrder.orderNo}/position`
  ) {
    respond(response, 200, {
      fulfillmentNo: reviewFulfillment.fulfillmentNo,
      orderNo: reviewOrder.orderNo,
      externalEventId: "review-signed-001",
      nodeType: "SIGNED",
      locationName: "杭州市",
      longitude: "120.155100",
      latitude: "30.274100",
      occurredAt: "2026-07-23T03:00:00Z",
    });
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/chat/conversations") {
    const actor = chatActor(request);
    const limit = Math.max(1, Math.min(100, Number(url.searchParams.get("limit") ?? 20)));
    const values = chatConversations
      .filter((conversation) =>
        actor.id === customer.id
          ? conversation.customerId === actor.id
          : conversation.status === "OPEN")
      .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))
      .slice(0, limit);
    respond(response, 200, values);
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/chat/conversations") {
    const actor = chatActor(request);
    if (actor.id !== customer.id) {
      respond(response, 403, null, "CHAT_CUSTOMER_REQUIRED", "Customer role required");
      return;
    }
    const input = await body(request);
    const existing = chatConversations.find((conversation) =>
      conversation.customerId === actor.id
      && conversation.clientConversationId === input.clientConversationId);
    if (existing) {
      respond(response, 200, existing);
      return;
    }
    const id = String(nextChatConversationId++);
    const created = {
      id,
      clientConversationId: input.clientConversationId,
      conversationNo: `CHAT-20260724-${String(chatConversations.length + 1).padStart(4, "0")}`,
      customerId: actor.id,
      assignedAgentId: null,
      subject: input.subject,
      contextType: input.contextType ?? null,
      contextId: input.contextId ?? null,
      status: "OPEN",
      lastMessageSequence: 0,
      unreadCount: 0,
      version: 0,
      createdAt: now,
      updatedAt: now,
    };
    chatConversations.push(created);
    chatMessages.set(id, []);
    respond(response, 200, created);
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/chat/websocket-tickets") {
    respond(response, 200, {
      ticket: `browser-ticket-${chatActor(request).id}`,
      targetPath: "/ws/chat",
      queryParameter: "ticket",
      expiresAt: "2026-07-24T00:05:00Z",
    });
    return;
  }
  const chatConversationMatch = /^\/api\/v1\/chat\/conversations\/(\d+)$/.exec(
    url.pathname,
  );
  if (chatConversationMatch?.[1] && method === "GET") {
    const selected = chatConversation(chatConversationMatch[1]);
    const actor = chatActor(request);
    if (!selected || (
      actor.id === customer.id
        ? selected.customerId !== actor.id
        : selected.assignedAgentId !== actor.id
    )) {
      respond(response, 404, null, "CHAT_CONVERSATION_NOT_FOUND", "Conversation not found");
      return;
    }
    respond(response, 200, selected);
    return;
  }
  const chatClaimMatch = /^\/api\/v1\/chat\/conversations\/(\d+)\/claim$/.exec(
    url.pathname,
  );
  if (chatClaimMatch?.[1] && method === "POST") {
    const selected = chatConversation(chatClaimMatch[1]);
    const actor = chatActor(request);
    if (!selected || actor.id !== admin.id) {
      respond(response, 403, null, "CHAT_AGENT_REQUIRED", "Support agent role required");
      return;
    }
    if (selected.status !== "OPEN") {
      respond(response, 409, null, "CONVERSATION_CLOSED", "Conversation is closed");
      return;
    }
    if (selected.assignedAgentId && selected.assignedAgentId !== actor.id) {
      respond(response, 409, null, "CHAT_ALREADY_CLAIMED", "Conversation already claimed");
      return;
    }
    const command = {
      conversationId: selected.id,
      operatorId: actor.id,
      attempt: adminChatClaimCommands.length + 1,
    };
    adminChatClaimCommands.push(command);
    selected.assignedAgentId = actor.id;
    selected.version += 1;
    selected.updatedAt = now;
    if (
      adminChatFixtureMode === "recovery-chain"
      && command.attempt === 1
    ) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "claim response lost after member fact committed",
      );
      return;
    }
    respond(response, 200, selected);
    return;
  }
  const chatCloseMatch = /^\/api\/v1\/chat\/conversations\/(\d+)\/close$/.exec(
    url.pathname,
  );
  if (chatCloseMatch?.[1] && method === "POST") {
    const selected = chatConversation(chatCloseMatch[1]);
    const actor = chatActor(request);
    const member = selected && (
      actor.id === customer.id
        ? selected.customerId === actor.id
        : selected.assignedAgentId === actor.id
    );
    if (!selected || !member) {
      respond(response, 403, null, "CONVERSATION_ACCESS_DENIED", "Conversation access denied");
      return;
    }
    const command = {
      conversationId: selected.id,
      operatorId: actor.id,
      attempt: adminChatCloseCommands.length + 1,
    };
    adminChatCloseCommands.push(command);
    if (selected.status !== "CLOSED") {
      selected.status = "CLOSED";
      selected.version += 1;
      selected.updatedAt = now;
    }
    if (
      adminChatFixtureMode === "recovery-chain"
      && command.attempt === 1
    ) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "close response lost after CLOSED committed",
      );
      return;
    }
    respond(response, 200, selected);
    return;
  }
  const chatMessagesMatch = /^\/api\/v1\/chat\/conversations\/(\d+)\/messages$/.exec(
    url.pathname,
  );
  if (chatMessagesMatch?.[1] && method === "GET") {
    const selected = chatConversation(chatMessagesMatch[1]);
    const actor = chatActor(request);
    if (
      selected
      && actor.id === admin.id
      && selected.assignedAgentId !== actor.id
    ) {
      adminChatPreClaimMessageReads += 1;
    }
    if (!selected || (
      actor.id === customer.id
        ? selected.customerId !== actor.id
        : selected.assignedAgentId !== actor.id
    )) {
      respond(response, 403, null, "CHAT_MESSAGE_ACCESS_DENIED", "Message access denied");
      return;
    }
    const before = url.searchParams.has("beforeSequence")
      ? Number(url.searchParams.get("beforeSequence"))
      : null;
    const size = Math.max(1, Math.min(100, Number(url.searchParams.get("size") ?? 50)));
    respond(response, 200, chatMessagePage(selected.id, before, size));
    return;
  }
  if (chatMessagesMatch?.[1] && method === "POST") {
    const selected = chatConversation(chatMessagesMatch[1]);
    const actor = chatActor(request);
    if (!selected || (
      actor.id === customer.id
        ? selected.customerId !== actor.id
        : selected.assignedAgentId !== actor.id
    )) {
      respond(response, 403, null, "CHAT_MESSAGE_ACCESS_DENIED", "Message access denied");
      return;
    }
    if (selected.status !== "OPEN") {
      respond(response, 409, null, "CONVERSATION_CLOSED", "Conversation is closed");
      return;
    }
    const input = await body(request);
    const messages = chatMessages.get(selected.id) ?? [];
    const serialized = JSON.stringify({
      clientMessageId: input.clientMessageId,
      messageType: input.messageType,
      content: input.content,
      attachmentUploadIds: input.attachmentUploadIds ?? [],
    });
    const existingCommand = adminChatSendCommands.find((command) =>
      command.clientMessageId === input.clientMessageId);
    if (existingCommand && existingCommand.serialized !== serialized) {
      respond(
        response,
        409,
        null,
        "IDEMPOTENCY_CONFLICT",
        "client message id was reused for different content",
      );
      return;
    }
    const command = existingCommand ?? {
      conversationId: selected.id,
      operatorId: actor.id,
      clientMessageId: String(input.clientMessageId ?? ""),
      content: String(input.content ?? ""),
      serialized,
      attempts: 0,
    };
    if (!existingCommand) {
      adminChatSendCommands.push(command);
    }
    command.attempts += 1;
    const existing = messages.find((message) =>
      message.senderId === actor.id
      && message.clientMessageId === input.clientMessageId);
    if (existing) {
      respond(response, 200, existing);
      return;
    }
    if (
      adminChatFixtureMode === "recovery-chain"
      && command.attempts === 1
    ) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "send request did not reach the Chat transaction",
      );
      return;
    }
    const created = {
      id: String(nextChatMessageId++),
      conversationId: selected.id,
      senderId: actor.id,
      clientMessageId: input.clientMessageId,
      sequence: selected.lastMessageSequence + 1,
      messageType: input.messageType,
      content: input.content,
      attachments: [],
      status: "STORED",
      createdAt: now,
    };
    messages.push(created);
    chatMessages.set(selected.id, messages);
    selected.lastMessageSequence = created.sequence;
    selected.unreadCount += 1;
    selected.updatedAt = now;
    respond(response, 200, created);
    return;
  }
  const chatReadMatch = /^\/api\/v1\/chat\/conversations\/(\d+)\/read$/.exec(
    url.pathname,
  );
  if (chatReadMatch?.[1] && method === "POST") {
    const selected = chatConversation(chatReadMatch[1]);
    const actor = chatActor(request);
    if (!selected || (
      actor.id === customer.id
        ? selected.customerId !== actor.id
        : selected.assignedAgentId !== actor.id
    )) {
      respond(response, 403, null, "CHAT_READ_ACCESS_DENIED", "Read access denied");
      return;
    }
    const input = await body(request);
    const message = (chatMessages.get(selected.id) ?? []).find((candidate) =>
      candidate.id === input.lastReadMessageId);
    if (!message) {
      respond(response, 404, null, "CHAT_MESSAGE_NOT_FOUND", "Message not found");
      return;
    }
    selected.unreadCount = 0;
    respond(response, 200, {
      conversationId: selected.id,
      lastReadMessageId: message.id,
      lastReadSequence: message.sequence,
      readAt: now,
    });
    return;
  }
  if (url.pathname === "/api/v1/identity/addresses" && method === "GET") {
    respond(response, 200, addressBook(request));
    return;
  }
  if (url.pathname === "/api/v1/identity/addresses" && method === "POST") {
    const ownerAddresses = addressBook(request);
    const input = await body(request);
    if (input.setDefault) {
      ownerAddresses.forEach((address) => {
        address.defaultAddress = false;
      });
    }
    const created = {
      ...input,
      id: nextAddressId(),
      postalCode: input.postalCode || null,
      defaultAddress: ownerAddresses.length === 0 || Boolean(input.setDefault),
      version: 0,
      createdAt: now,
      updatedAt: now,
    };
    delete created.setDefault;
    ownerAddresses.push(created);
    respond(response, 200, created);
    return;
  }
  const addressMatch = /^\/api\/v1\/identity\/addresses\/(\d+)(\/default)?$/.exec(url.pathname);
  if (addressMatch?.[1] && method === "PUT") {
    const ownerAddresses = addressBook(request);
    const index = ownerAddresses.findIndex((address) => address.id === addressMatch[1]);
    if (index < 0) {
      respond(response, 404, null, "ADDRESS_NOT_FOUND", "Address not found");
      return;
    }
    const input = await body(request);
    if (input.setDefault) {
      ownerAddresses.forEach((address) => {
        address.defaultAddress = false;
      });
    }
    ownerAddresses[index] = {
      ...ownerAddresses[index],
      ...input,
      postalCode: input.postalCode || null,
      defaultAddress: input.setDefault || ownerAddresses[index].defaultAddress,
      version: ownerAddresses[index].version + 1,
      updatedAt: now,
    };
    delete ownerAddresses[index].setDefault;
    respond(response, 200, ownerAddresses[index]);
    return;
  }
  if (addressMatch?.[1] && addressMatch[2] === "/default" && method === "POST") {
    const ownerAddresses = addressBook(request);
    const selected = ownerAddresses.find((address) => address.id === addressMatch[1]);
    if (!selected) {
      respond(response, 404, null, "ADDRESS_NOT_FOUND", "Address not found");
      return;
    }
    const wasDefault = selected.defaultAddress;
    ownerAddresses.forEach((address) => {
      address.defaultAddress = address.id === selected.id;
    });
    if (!wasDefault) {
      selected.version += 1;
    }
    respond(response, 200, selected);
    return;
  }
  if (addressMatch?.[1] && !addressMatch[2] && method === "DELETE") {
    const ownerAddresses = addressBook(request);
    const index = ownerAddresses.findIndex((address) => address.id === addressMatch[1]);
    if (index < 0) {
      respond(response, 404, null, "ADDRESS_NOT_FOUND", "Address not found");
      return;
    }
    const [removed] = ownerAddresses.splice(index, 1);
    if (removed?.defaultAddress && ownerAddresses[0]) {
      ownerAddresses[0].defaultAddress = true;
      ownerAddresses[0].version += 1;
    }
    respond(response, 200, null);
    return;
  }
  const cartItemMatch = /^\/api\/v1\/trade\/cart\/items\/(\d+)$/.exec(url.pathname);
  if (cartItemMatch?.[1] && method === "PUT") {
    const ownerCart = cartBook(request);
    const input = await body(request);
    const skuId = cartItemMatch[1];
    const productId = String(input.productId ?? "");
    const quantity = Number(input.quantity);
    const index = ownerCart.findIndex((item) => item.skuId === skuId);
    const existing = index >= 0 ? ownerCart[index] : null;
    const snapshot = cartSnapshot(productId, skuId, existing);
    if (
      !snapshot
      || !Number.isInteger(quantity)
      || quantity < 1
      || quantity > 1_000_000_000
    ) {
      respond(response, 400, null, "INVALID_CART_ITEM", "Invalid cart item");
      return;
    }
    const updated = {
      id: existing?.id ?? nextCartItemId(),
      productId,
      skuId,
      ...snapshot,
      quantity,
      selected: Boolean(input.selected),
    };
    if (index >= 0) {
      ownerCart[index] = updated;
    } else {
      ownerCart.push(updated);
    }
    respond(response, 200, updated);
    return;
  }
  if (cartItemMatch?.[1] && method === "DELETE") {
    const ownerCart = cartBook(request);
    const index = ownerCart.findIndex((item) => item.skuId === cartItemMatch[1]);
    if (index >= 0) {
      ownerCart.splice(index, 1);
    }
    respond(response, 200, null);
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/trade/cart/guest-merge") {
    const ownerCart = cartBook(request);
    const actor = identityActor(request);
    const idempotencyKey = String(request.headers["idempotency-key"] ?? "");
    const input = await body(request);
    const requestedItems = Array.isArray(input.items) ? input.items : [];
    const canonical = JSON.stringify([...requestedItems].sort((left, right) =>
      String(left.skuId).localeCompare(String(right.skuId))));
    const requestIdentity = `${actor.id}:${idempotencyKey}`;
    const existingRequest = cartMergeRequests.get(requestIdentity);
    if (existingRequest && existingRequest !== canonical) {
      respond(response, 409, null, "IDEMPOTENCY_CONFLICT", "Merge key payload conflict");
      return;
    }
    if (!existingRequest) {
      for (const requested of requestedItems) {
        const productId = String(requested.productId ?? "");
        const skuId = String(requested.skuId ?? "");
        const quantity = Number(requested.quantity);
        const index = ownerCart.findIndex((item) => item.skuId === skuId);
        const existing = index >= 0 ? ownerCart[index] : null;
        const snapshot = cartSnapshot(productId, skuId, existing);
        if (!snapshot || !Number.isInteger(quantity) || quantity < 1) {
          respond(response, 400, null, "INVALID_CART_MERGE", "Invalid merge item");
          return;
        }
        if (existing) {
          existing.quantity = Math.min(1_000_000_000, existing.quantity + quantity);
          existing.selected = true;
        } else {
          ownerCart.push({
            id: nextCartItemId(),
            productId,
            skuId,
            ...snapshot,
            quantity,
            selected: true,
          });
        }
      }
      cartMergeRequests.set(requestIdentity, canonical);
    }
    respond(response, 200, ownerCart);
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/trade/cart/items") {
    respond(response, 200, cartBook(request));
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/api/v1/marketing/admin/rules"
  ) {
    const input = await body(request);
    const ruleCode = String(input.ruleCode ?? "");
    const existing = marketingRules.find((rule) =>
      rule.ruleCode === ruleCode);
    if (existing) {
      respond(
        response,
        409,
        null,
        "DUPLICATE_RESOURCE",
        "marketing rule code already exists",
      );
      return;
    }
    const rule = {
      ruleCode,
      name: String(input.name ?? ""),
      benefitType: String(input.benefitType ?? ""),
      thresholdAmount: String(input.thresholdAmount ?? ""),
      discountAmount: String(input.discountAmount ?? ""),
      stackOrder: Number(input.stackOrder ?? 0),
      validFrom: String(input.validFrom ?? ""),
      validUntil: String(input.validUntil ?? ""),
      status: "ACTIVE",
      regions: Array.isArray(input.regions) ? input.regions : [],
      version: 0,
    };
    marketingRules.push(rule);
    marketingCommands.push({
      kind: "rule",
      referenceNo: ruleCode,
      commandKey: ruleCode,
      payload: input,
      attempts: 1,
    });
    if (marketingFixtureMode === "rule-unknown") {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost after marketing rule creation committed",
      );
      return;
    }
    respond(response, 200, rule);
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/api/v1/marketing/admin/benefits"
  ) {
    const input = await body(request);
    const userId = String(input.userId ?? "");
    const ruleCode = String(input.ruleCode ?? "");
    const grantKey = String(input.grantKey ?? "");
    const existing = marketingCommands.find((command) =>
      command.kind === "grant"
      && command.userId === userId
      && command.commandKey === grantKey);
    if (existing && existing.ruleCode !== ruleCode) {
      respond(
        response,
        409,
        null,
        "IDEMPOTENCY_CONFLICT",
        "grant key belongs to another marketing rule",
      );
      return;
    }
    const rule = marketingRules.find((candidate) =>
      candidate.ruleCode === ruleCode);
    if (!rule) {
      respond(
        response,
        404,
        null,
        "RESOURCE_NOT_FOUND",
        "marketing rule does not exist",
      );
      return;
    }
    const command = existing ?? {
      kind: "grant",
      referenceNo: `${userId} / ${ruleCode}`,
      commandKey: grantKey,
      userId,
      ruleCode,
      payload: input,
      attempts: 0,
      benefitNo: `BEN${nextMarketingBenefitId}`,
    };
    if (!existing) {
      nextMarketingBenefitId += 1n;
      marketingCommands.push(command);
      marketingBenefits.push({
        benefitNo: command.benefitNo,
        userId,
        ruleCode,
        benefitType: rule.benefitType,
        thresholdAmount: rule.thresholdAmount,
        discountAmount: rule.discountAmount,
        status: "AVAILABLE",
        lockedOrderNo: null,
        redeemedOrderNo: null,
        validFrom: rule.validFrom,
        validUntil: rule.validUntil,
        regions: rule.regions,
      });
    }
    command.attempts += 1;
    if (
      marketingFixtureMode === "grant-retry"
      && command.attempts === 1
    ) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost after marketing benefit grant committed",
      );
      return;
    }
    respond(
      response,
      200,
      marketingBenefits.find((item) =>
        item.benefitNo === command.benefitNo),
    );
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/marketing/benefits") {
    respond(response, 200, [benefit]);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/inventory/stocks/${reviewProduct.skus[0].id}`
  ) {
    respond(response, 200, {
      skuId: reviewProduct.skus[0].id,
      onHand: 20,
      reserved: 2,
      available: 18,
    });
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/trade/after-sales") {
    respond(response, 200, [afterSale]);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/trade/after-sales/${afterSale.afterSaleNo}`
  ) {
    respond(response, 200, afterSale);
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/fulfillment/returns") {
    respond(response, 200, [returnReceipt]);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/fulfillment/returns/${returnReceipt.returnReceiptNo}`
  ) {
    respond(response, 200, returnReceipt);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/returns/${returnReceipt.returnReceiptNo}/shipment`
  ) {
    const input = await body(request);
    returnReceipt.status = "RETURNING";
    returnReceipt.carrier = input.carrier;
    returnReceipt.trackingNo = input.trackingNo;
    returnReceipt.shippedAt = now;
    returnReceipt.version += 1;
    afterSale.status = "RETURNING";
    respond(response, 200, returnReceipt);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/payment/refunds/by-after-sale/${afterSale.afterSaleNo}`
  ) {
    respond(response, 200, refund);
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/fulfillment/admin/orders") {
    respond(response, 200, [fulfillment]);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/fulfillment/admin/orders/${fulfillment.fulfillmentNo}`
  ) {
    respond(response, 200, fulfillment);
    return;
  }
  if (
    method === "GET"
    && url.pathname === "/api/v1/fulfillment/admin/geo/nearby"
  ) {
    respond(response, 200, [nearbyShipmentPosition]);
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/api/v1/fulfillment/admin/geo/cache/rebuild"
  ) {
    respond(response, 200, { scanned: 1, cached: 1 });
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/admin/orders/${fulfillment.fulfillmentNo}/picking`
  ) {
    fulfillment.status = "PICKING";
    fulfillment.version += 1;
    fulfillment.pickedAt = now;
    respond(response, 200, fulfillment);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/admin/orders/${fulfillment.fulfillmentNo}/packed`
  ) {
    fulfillment.status = "PACKED";
    fulfillment.version += 1;
    fulfillment.packedAt = now;
    respond(response, 200, fulfillment);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/admin/orders/${fulfillment.fulfillmentNo}/ship`
  ) {
    const input = await body(request);
    fulfillment.status = "SHIPPED";
    fulfillment.carrier = String(input.carrier ?? "");
    fulfillment.trackingNo = String(input.trackingNo ?? "");
    fulfillment.version += 1;
    fulfillment.shippedAt = now;
    respond(response, 200, fulfillment);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/admin/orders/${fulfillment.fulfillmentNo}/traces`
  ) {
    const input = await body(request);
    const commandKey = String(input.externalEventId ?? "");
    const serialized = JSON.stringify(input);
    const existing = fulfillmentCommands.find((command) =>
      command.kind === "trace" && command.commandKey === commandKey);
    if (existing && existing.serialized !== serialized) {
      respond(
        response,
        409,
        null,
        "IDEMPOTENCY_CONFLICT",
        "trace event id was reused for different data",
      );
      return;
    }
    const command = existing ?? {
      kind: "trace",
      referenceNo: fulfillment.fulfillmentNo,
      commandKey,
      serialized,
      payload: input,
      attempts: 0,
    };
    if (!existing) {
      fulfillmentCommands.push(command);
    }
    command.attempts += 1;
    if (command.attempts === 1) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost before the trace result was returned",
      );
      return;
    }
    if (!fulfillment.traces.some((trace) =>
      trace.externalEventId === commandKey)) {
      fulfillment.traces.push({
        externalEventId: commandKey,
        nodeType: input.nodeType,
        description: input.description,
        locationName: input.locationName ?? null,
        longitude: input.longitude ?? null,
        latitude: input.latitude ?? null,
        occurredAt: input.occurredAt,
      });
      fulfillment.status = input.nodeType === "SIGNED"
        ? "SIGNED"
        : input.nodeType === "DELIVERING"
          ? "DELIVERING"
          : input.nodeType === "EXCEPTION"
            ? "EXCEPTION"
            : "IN_TRANSIT";
      fulfillment.version += 1;
    }
    respond(response, 200, fulfillment);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/admin/orders/${fulfillment.fulfillmentNo}/exception`
  ) {
    const input = await body(request);
    fulfillment.status = "EXCEPTION";
    fulfillment.version += 1;
    fulfillment.history.push({
      fromStatus: "PICKING",
      toStatus: "EXCEPTION",
      command: "MARK_EXCEPTION",
      reason: String(input.reason ?? ""),
      operatorType: "WAREHOUSE",
      operatorId: admin.id,
      createdAt: now,
    });
    respond(response, 200, fulfillment);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/admin/orders/${fulfillment.fulfillmentNo}/exception/resolve`
  ) {
    const input = await body(request);
    const commandKey = String(request.headers["idempotency-key"] ?? "");
    const reason = String(input.reason ?? "");
    const existing = fulfillmentCommands.find((command) =>
      command.kind === "resolve" && command.commandKey === commandKey);
    if (
      existing
      && (
        existing.referenceNo !== fulfillment.fulfillmentNo
        || existing.reason !== reason
      )
    ) {
      respond(
        response,
        409,
        null,
        "IDEMPOTENCY_CONFLICT",
        "exception command id was reused for different data",
      );
      return;
    }
    const command = existing ?? {
      kind: "resolve",
      referenceNo: fulfillment.fulfillmentNo,
      commandKey,
      reason,
      attempts: 0,
    };
    if (!existing) {
      fulfillmentCommands.push(command);
    }
    command.attempts += 1;
    if (command.attempts === 1) {
      fulfillment.status = "PICKING";
      fulfillment.version += 1;
      fulfillment.history.push({
        fromStatus: "EXCEPTION",
        toStatus: "PICKING",
        command: "RESOLVE_EXCEPTION",
        reason,
        operatorType: "ADMIN",
        operatorId: admin.id,
        createdAt: now,
      });
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost after exception recovery committed",
      );
      return;
    }
    respond(response, 200, fulfillment);
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/fulfillment/admin/returns") {
    respond(response, 200, [returnReceipt]);
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/fulfillment/admin/returns/${returnReceipt.returnReceiptNo}`
  ) {
    respond(response, 200, returnReceipt);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/admin/returns/${returnReceipt.returnReceiptNo}/receive`
  ) {
    returnReceipt.status = "RECEIVED";
    returnReceipt.version += 1;
    returnReceipt.receivedAt = now;
    respond(response, 200, returnReceipt);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/fulfillment/admin/returns/${returnReceipt.returnReceiptNo}/inspect`
  ) {
    const input = await body(request);
    returnReceipt.status = "INSPECTED";
    returnReceipt.version += 1;
    returnReceipt.inspectionRemark = String(input.remark ?? "");
    returnReceipt.inspectedAt = now;
    respond(response, 200, returnReceipt);
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/trade/admin/after-sales") {
    const expectedStatus = url.searchParams.get("status");
    respond(
      response,
      200,
      expectedStatus && expectedStatus !== adminAfterSale.status
        ? []
        : [adminAfterSale],
    );
    return;
  }
  if (
    method === "GET"
    && url.pathname === `/api/v1/trade/admin/after-sales/${adminAfterSale.afterSaleNo}`
  ) {
    respond(response, 200, adminAfterSale);
    return;
  }
  if (
    method === "POST"
    && url.pathname === `/api/v1/trade/admin/after-sales/${adminAfterSale.afterSaleNo}/review`
  ) {
    const input = await body(request);
    adminAfterSaleReviewCommands.push({
      approved: input.approved === true,
      reason: String(input.reason ?? ""),
      attempt: adminAfterSaleReviewCommands.length + 1,
    });
    const firstAttempt = adminAfterSaleReviewCommands.length === 1;
    if (
      adminAfterSaleFixtureMode === "commit-lost"
      && firstAttempt
    ) {
      applyAdminAfterSaleReview(input);
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost after Trade review committed",
      );
      return;
    }
    if (
      adminAfterSaleFixtureMode === "retry-required"
      && firstAttempt
    ) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "review request did not reach Trade",
      );
      return;
    }
    if (!applyAdminAfterSaleReview(input)) {
      respond(
        response,
        409,
        null,
        "INVALID_STATE",
        "after-sale is no longer waiting for review",
      );
      return;
    }
    respond(response, 200, adminAfterSale);
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/api/v1/inventory/admin/warehouses"
  ) {
    const input = await body(request);
    const code = String(input.code ?? "");
    const name = String(input.name ?? "");
    const existing = inventoryWarehouses.find((warehouse) =>
      warehouse.code === code);
    if (existing) {
      respond(
        response,
        409,
        null,
        "DUPLICATE_RESOURCE",
        "warehouse code already exists",
      );
      return;
    }
    const warehouse = {
      id: "2079000000000004999",
      code,
      name,
      status: "ACTIVE",
      version: 0,
    };
    inventoryWarehouses.push(warehouse);
    inventoryCommands.push({
      kind: "warehouse",
      referenceNo: code,
      commandKey: code,
      payload: input,
      attempts: 1,
    });
    if (inventoryFixtureMode === "warehouse-authority") {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost after warehouse creation committed",
      );
      return;
    }
    respond(response, 200, warehouse);
    return;
  }
  if (method === "GET" && url.pathname === "/api/v1/inventory/admin/warehouses") {
    respond(response, 200, inventoryWarehouses);
    return;
  }
  if (
    method === "POST"
    && url.pathname === "/api/v1/inventory/admin/stocks/adjustments"
  ) {
    const input = await body(request);
    const movementNo = String(input.movementNo ?? "");
    const serialized = JSON.stringify(input);
    const existing = inventoryCommands.find((command) =>
      command.kind === "adjustment"
      && command.commandKey === movementNo);
    if (existing && existing.serialized !== serialized) {
      respond(
        response,
        409,
        null,
        "IDEMPOTENCY_CONFLICT",
        "movement number was reused for different data",
      );
      return;
    }
    const command = existing ?? {
      kind: "adjustment",
      referenceNo: movementNo,
      commandKey: movementNo,
      serialized,
      payload: input,
      attempts: 0,
      applied: false,
    };
    if (!existing) {
      inventoryCommands.push(command);
    }
    command.attempts += 1;
    if (!command.applied) {
      inventoryStock.onHand += Number(input.quantityDelta ?? 0);
      inventoryStock.available =
        inventoryStock.onHand - inventoryStock.reserved;
      inventoryStock.version += 1;
      command.applied = true;
    }
    if (
      inventoryFixtureMode === "adjustment-retry"
      && command.attempts === 1
    ) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost after stock adjustment committed",
      );
      return;
    }
    respond(response, 200, inventoryStock);
    return;
  }
  const inventoryStockPositionMatch =
    /^\/api\/v1\/inventory\/admin\/warehouses\/([^/]+)\/stocks\/([^/]+)$/u
      .exec(url.pathname);
  if (method === "GET" && inventoryStockPositionMatch) {
    const warehouseId = decodeURIComponent(inventoryStockPositionMatch[1]);
    const skuId = decodeURIComponent(inventoryStockPositionMatch[2]);
    if (
      warehouseId !== inventoryStock.warehouseId
      || skuId !== inventoryStock.skuId
    ) {
      respond(
        response,
        404,
        null,
        "RESOURCE_NOT_FOUND",
        "stock position does not exist",
      );
      return;
    }
    respond(response, 200, inventoryStock);
    return;
  }
  const governanceRefundRetryMatch =
    /^\/api\/v1\/payment\/admin\/refunds\/([^/]+)\/retry-dispatch$/
      .exec(url.pathname);
  if (governanceRefundRetryMatch?.[1] && method === "POST") {
    const referenceNo = decodeURIComponent(governanceRefundRetryMatch[1]);
    const input = await body(request);
    const commandId = String(request.headers["idempotency-key"] ?? "");
    const reason = String(input.reason ?? "");
    const existing = governanceRefundCommands.find((command) =>
      command.commandId === commandId);
    if (
      existing
      && (
        existing.referenceNo !== referenceNo
        || existing.reason !== reason
      )
    ) {
      respond(
        response,
        409,
        null,
        "IDEMPOTENCY_CONFLICT",
        "command id was reused for different data",
      );
      return;
    }
    const command = existing ?? {
      commandId,
      referenceNo,
      reason,
      attempts: 0,
    };
    if (!existing) {
      governanceRefundCommands.push(command);
    }
    command.attempts += 1;
    governanceRefund.requestStatus = "PENDING";
    governanceRefund.requestAttempts = 0;
    governanceRefund.nextRequestAt = now;
    governanceRefund.updatedAt = now;
    if (
      governanceFixtureMode === "audit-confirmed"
      && !governanceRefundAudits.some((audit) =>
        audit.commandId === command.commandId)
    ) {
      governanceRefundAudits.unshift(governanceRefundAudit(command));
    }
    if (command.attempts === 1) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost after the Payment transaction committed",
      );
      return;
    }
    if (!governanceRefundAudits.some((audit) =>
      audit.commandId === command.commandId)) {
      governanceRefundAudits.unshift(governanceRefundAudit(command));
    }
    respond(response, 200, governanceRefund);
    return;
  }
  const governanceRefundAuditsMatch =
    /^\/api\/v1\/payment\/admin\/refunds\/([^/]+)\/retry-dispatch\/audits$/
      .exec(url.pathname);
  if (governanceRefundAuditsMatch?.[1] && method === "GET") {
    const referenceNo = decodeURIComponent(governanceRefundAuditsMatch[1]);
    respond(
      response,
      200,
      governanceRefundAudits.filter((audit) =>
        audit.refundNo === referenceNo),
    );
    return;
  }
  const governanceExceptionRefundMatch =
    /^\/api\/v1\/payment\/admin\/payments\/([^/]+)\/exception-refunds$/
      .exec(url.pathname);
  if (governanceExceptionRefundMatch?.[1] && method === "POST") {
    const referenceNo = decodeURIComponent(governanceExceptionRefundMatch[1]);
    const input = await body(request);
    const commandId = String(request.headers["idempotency-key"] ?? "");
    const reason = String(input.reason ?? "");
    const existing = governanceExceptionCommands.find((command) =>
      command.commandId === commandId);
    if (
      existing
      && (
        existing.referenceNo !== referenceNo
        || existing.reason !== reason
      )
    ) {
      respond(
        response,
        409,
        null,
        "IDEMPOTENCY_CONFLICT",
        "command id was reused for different data",
      );
      return;
    }
    const command = existing ?? {
      commandId,
      referenceNo,
      reason,
      attempts: 0,
    };
    if (!existing) {
      governanceExceptionCommands.push(command);
    }
    command.attempts += 1;
    if (
      governanceFixtureMode === "audit-confirmed"
      && !governanceExceptionAudits.some((audit) =>
        audit.commandId === command.commandId)
    ) {
      governanceExceptionAudits.unshift(governanceExceptionAudit(command));
    }
    if (command.attempts === 1) {
      respond(
        response,
        503,
        null,
        "SERVICE_UNAVAILABLE",
        "response lost after the refund was created",
      );
      return;
    }
    if (!governanceExceptionAudits.some((audit) =>
      audit.commandId === command.commandId)) {
      governanceExceptionAudits.unshift(governanceExceptionAudit(command));
    }
    respond(response, 200, governanceExceptionRefund);
    return;
  }
  const governanceExceptionAuditsMatch =
    /^\/api\/v1\/payment\/admin\/payments\/([^/]+)\/exception-refunds\/audits$/
      .exec(url.pathname);
  if (governanceExceptionAuditsMatch?.[1] && method === "GET") {
    const referenceNo = decodeURIComponent(governanceExceptionAuditsMatch[1]);
    respond(
      response,
      200,
      governanceExceptionAudits.filter((audit) =>
        audit.paymentNo === referenceNo),
    );
    return;
  }
  if (
    method === "GET"
    && /^\/api\/v1\/(trade|payment|inventory|fulfillment)\/admin\/reconciliation\/issues$/
      .test(url.pathname)
  ) {
    respond(response, 200, []);
    return;
  }
  if (method === "POST" && url.pathname === "/api/v1/marketing/pricing-previews") {
    const input = await body(request);
    const selected = Array.isArray(input.benefitNos) && input.benefitNos.includes(benefit.benefitNo);
    respond(response, 200, {
      originalAmount: input.originalAmount,
      couponDiscount: selected ? "10.00" : "0.00",
      redPacketDiscount: "0.00",
      subsidyDiscount: "0.00",
      discountAmount: selected ? "10.00" : "0.00",
      payableAmount: selected ? "368.00" : input.originalAmount,
      appliedBenefits: selected ? [{
        benefitNo: benefit.benefitNo,
        ruleCode: benefit.ruleCode,
        benefitType: benefit.benefitType,
        discountAmount: "10.00",
        allocations: [{
          lineNo: 1,
          skuId: cartItems[0].skuId,
          benefitNo: benefit.benefitNo,
          ruleCode: benefit.ruleCode,
          benefitType: benefit.benefitType,
          discountAmount: "10.00",
        }],
      }] : [],
      calculatedAt: now,
    });
    return;
  }

  respond(response, 404, null, "NOT_FOUND", `${method} ${url.pathname} is not mocked`);
}).listen(port, "127.0.0.1", () => {
  console.log(`PlainJournal browser mock API listening on http://127.0.0.1:${port}`);
});
