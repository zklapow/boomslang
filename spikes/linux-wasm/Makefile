BUILD_DIR ?= $(CURDIR)/build
JOEL_CMDLINE ?= nosmp nr_cpus=1 maxcpus=1 root=/dev/ram0 rootfstype=ramfs init=/init console=hvc console=ttyS0

.PHONY: fetch fetch-joel-demo native-smoke wasm joel-chicory joel-chicory-scripted clean

fetch:
	./scripts/fetch-mini-rv32ima.sh "$(BUILD_DIR)"

fetch-joel-demo:
	./scripts/fetch-joel-demo.sh "$(BUILD_DIR)/joel-demo"

native-smoke: fetch
	./scripts/native-smoke.sh "$(BUILD_DIR)"

wasm: fetch
	./scripts/build-mini-rv32ima-wasm.sh "$(BUILD_DIR)"

joel-chicory: fetch-joel-demo
	./scripts/run-joel-chicory.sh --cmdline "$(JOEL_CMDLINE)"

joel-chicory-scripted: fetch-joel-demo
	stdin_text=$$(printf 'echo chicory-linux-ok\nuname -a\n.'); \
	timeout 60s ./scripts/run-joel-chicory.sh --cmdline "$(JOEL_CMDLINE)" --stdin-text "$${stdin_text%.}"; \
	status=$$?; [ $$status -eq 0 ] || [ $$status -eq 124 ]

clean:
	rm -rf "$(BUILD_DIR)"
