"""Verify the single-currency contract using a fresh synthetic order."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("p0_payment", HERE / "verify-p0-payment-recovery.py")
payment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payment)


class Currency(payment.PaymentCases):
    def execute(self):
        super().execute()
        verified_cases = self.result["cases"]
        self.result.update(status="INCONCLUSIVE", cases=[], prerequisite_cases=verified_cases)
        self.stage = "fixed-currency-contract"
        frontend = payment.base.REPO / "frontend/packages/foundation/src/format.ts"
        assert 'currency: "CNY"' in frontend.read_text()
        order = "BOOT412-CURRENCY-USD"
        self.sql(f"""
INSERT INTO b1_trade.trade_order
(id,order_no,user_id,idempotency_key,request_hash,reservation_no,warehouse_code,warehouse_id,status,
 total_amount,original_amount,payment_deadline,created_at,updated_at)
VALUES (93,'{order}',91001,'{order}',REPEAT('0',64),'RSV-{order}','P0-FIXTURE',841002,
 'PENDING_PAYMENT',39.80,39.80,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
""")
        key = "FOREIGN-CURRENCY-WITNESS"
        self.stage = "extra-currency-input"
        response = self.get("payment", payment.PAYMENT_PATH, {"orderNo":order, "channel":"MOCK",
                            "currency":"USD"}, self.headers(key))
        self.save("extra-currency-response.json", response)
        ledger = self.facts(order)
        if response["status"] == 200:
            actual = response["body"]["data"]
            assert actual["amount"] == ledger["amount"] == 39.8
            assert actual["status"] == "PROCESSING" and ledger["payments"] == 1
            query = self.data("currency-intent-readback", self.get("payment",
                payment.PAYMENT_PATH + "/by-idempotency-key/" + key, headers=self.customer))
            assert query["paymentNo"] == actual["paymentNo"] and query["amount"] == 39.8
            handling = "IGNORED_WITH_UNCHANGED_ORDER_AMOUNT"
        else:
            assert response["status"] in (400, 422) and ledger["payments"] == 0
            handling = "REJECTED"
        assert ledger["transactions"] == ledger["callbacks"] == ledger["refunds"] == ledger["successEvents"] == 0
        assert ledger["orderState"] == "PENDING_PAYMENT"
        self.save("unsupported-currency-ledger.json", ledger)
        self.host_check("host-after-currency.json")
        self.result.update(status="PASS_SCOPED", cases=["P0-10-single-currency-input"],
                           intended_currency="CNY", currency_input="USD", handling=handling,
                           scope="real Trade, Payment and MySQL, canonical amount, no settlement triggered")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    runner = Currency(parser.parse_args())
    try:
        runner.execute()
    except Exception as error:
        runner.result.update(status="INCONCLUSIVE", stage=runner.stage, error=repr(error))
    finally:
        runner.cleanup()
        runner.save("result.json", runner.result)
    print(json.dumps(runner.result))
    return 0 if runner.result["status"] == "PASS_SCOPED" and runner.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())