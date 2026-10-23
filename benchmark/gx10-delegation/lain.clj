;; Run from the datalog research root. The editor evaluates declarations only.
(load-file "examples/lain/gx10_delegation/host.clj")
(apply gx10-delegation.host/-main *command-line-args*)
