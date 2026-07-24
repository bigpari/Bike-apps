import asyncio
import websockets
import json
import datetime
import os

from decoder import BikeDecoder


DATA_FOLDER = "captures"
os.makedirs(DATA_FOLDER, exist_ok=True)


def timestamp():
    return datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")


class CaptureSession:
    def __init__(self):
        self.filename = f"{DATA_FOLDER}/session_{timestamp()}.jsonl"
        print(f"Saving capture: {self.filename}")

    def save(self, obj):
        with open(self.filename, "a", encoding="utf-8") as f:
            f.write(json.dumps(obj) + "\n")


async def handle_connection(websocket):
    print("==============================")
    print("Android connected")
    print("Client:", websocket.remote_address)
    print("==============================")

    session = CaptureSession()
    decoder = BikeDecoder()
    record_number = 0

    try:
        async for message in websocket:
            now = datetime.datetime.now().isoformat()
            
            print("\nReceived raw message payload.")
            
            try:
                packet = json.loads(message)
            except Exception:
                continue

            # Save every message exactly as received
            session.save({
                "time": now,
                "type": packet.get("type"),
                "data": packet.get("data")
            })

             # Process BLE packets
            if packet.get("type") == "BLE_DATA":
                raw_hex = packet.get("data", "")
                
                try:
                    ble = bytes.fromhex(raw_hex)
                    decoded = decoder.process(ble)

                    if decoded:
                        record_number += 1
                        
                        # Raw history arrays are read safely BEFORE the reset
                        full_raw_record = {
                            "record": record_number,
                            "time": now,
                            "E5": list(decoder.e5),
                            "E6": list(decoder.e6),
                            "E7": list(decoder.e7)
                        }

                        print("\n========== RECORD STRUCTURE COMPLETE ==========")
                        print(json.dumps(full_raw_record, indent=4))
                        print("\n===== DECODED PERFORMANCE METRICS =====")
                        print(json.dumps(decoded, indent=4))

                        session.save({
                            "type": "RECORD",
                            "data": full_raw_record
                        })

                        session.save({
                            "time": now,
                            "type": "DECODED",
                            "data": decoded
                        })

                        # Reset the decoder state now that logging is finished
                        decoder.reset()

                except Exception as e:
                    print("Decoder processing exception pipeline:", e)

            await websocket.send(json.dumps({"status": "received"}))

    except websockets.exceptions.ConnectionClosed:
        print("Android disconnected cleanly")
    except Exception as e:
        print("Server operating exception error:", e)


async def main():
    async with websockets.serve(handle_connection, "0.0.0.0", 8080):
        print("Server running on port 8080")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
