(ns reindeer-maze.core
  (:require [clojure.string :refer [join trim upper-case]]
            [maze.generate :refer [generate-maze]]
            [quil.applet :refer [applet-close]]
            [reindeer-maze.color :refer [palette]]
            [reindeer-maze.navigation :refer [path-between
                                              possible-moves
                                              random-free-position]]
            [reindeer-maze.net :refer [create-network-server
                                       read-from
                                       writeln]]
            [reindeer-maze.render :refer :all]
            [reindeer-maze.util :refer [fmap make-odd str-to-int]]))

(defprotocol GameToken
  (move [player movement])
  (randomize-position [player maze]))

(defprotocol NetworkPlayer
  (receive-message [player])
  (send-message [player str])
  (disconnect [player]))

(defrecord Player
    [socket name position color])

(extend-type Player
  GameToken
  (move [player movement]
    (update-in player [:position]
               #(map + % movement)))
  (randomize-position [player maze]
    (merge player
           {:position (random-free-position maze)})))

(extend-type Player
  NetworkPlayer
  (receive-message [player]
    (read-from (:socket player)))

  (send-message [player str]
    (writeln (:socket player) str))

  (disconnect [player]
    (.close (:socket player))))

(defprotocol MazeGame
  (new-maze [game-state size])
  (join-game [game-state player])
  (leave-game [game-state player])
  (possible-moves-for-player [game-state player])
  (valid-move? [game-state player movement])
  (move-player [game-state player movement])
  (randomize-player-positions [game-state]))

(defrecord GameState
    [maze goal-position players])

(extend-type GameState
  MazeGame
  (new-maze
    [game-state size]
    (let [center (map (fn [x] (make-odd (int (/ x 2))))
                      size)
          new-maze (generate-maze size center)]
      (-> game-state
          (merge
           {:goal-position center
            :maze new-maze})
          randomize-player-positions)))

  (join-game
    [game-state player]
    (let [{maze :maze} game-state]
      (update-in game-state
                 [:players]
                 merge {(:socket player) player})))

  (leave-game
    [game-state player]
    (update-in game-state
               [:players]
               (fn [players]
                 (disconnect player)
                 (dissoc players (:socket player)))))

  (possible-moves-for-player
    [game-state player]
    (let [{:keys [maze goal-position players]} game-state
          player (players (:socket player))]
      (merge (possible-moves maze (:position player))
             {:goal-direction (path-between (:position player) goal-position maze)})))

  (valid-move?
    [game-state player movement]
    (let [maze (:maze game-state)
          position (:position player)
          [new-x new-y] (map + position movement)]
      (nil? (get-in maze [new-y new-x]))))

  (move-player
    [game-state player movement]
    (if (valid-move? game-state player movement)
      (update-in game-state [:players (:socket player)]
                 move movement)
      game-state))

  (randomize-player-positions
    [game-state]
    (update-in game-state [:players]
               #(fmap % randomize-position (:maze game-state)))))


(def help-text
  (join "\n"
        ["===="
         "Help:"
         "-----"
         "Type N, E, S or W to move."
         ""
         "I will normally answer something like, 'N6 E0 S2 W0 P?',"
         "which means I can move north for 6 squares, south for 2,"
         "and I can't see the presents;"
         "or 'N0 E4 S0 W0 PE', which means I can move east for 4 squares"
         "and I can see the presents to the east."
         ""
         ""
         "If I say 'WIN' or 'LOSE', this round is over, and we're"
         "going straight on to the next."
         "===="]))

(defn command-to-movement
  [request]
  (if request
    (case (-> request
              trim
              upper-case)
      "N" [0 -1]
      "S" [0 1]
      "E" [1 0]
      "W" [-1 0]
      nil)))

(defn format-possible-moves
  [moves]
  (let [{:keys [north south east west goal-direction]} moves]
    (format "N%d E%d S%d W%d P%s"
            north east south west
            (case goal-direction
              :north "N"
              :south "S"
              :west "W"
              :east "E"
              :hit "X"
              "?"))))

;; TODO Tidy
(defn client-handler
  [game-state-atom socket]
  (writeln socket "Language/team name?")

  (let [sleep-time-ms 100
        name (read-from socket)
        random-color (first (shuffle palette))
        new-player (-> (->Player socket name nil random-color)
                       (randomize-position (:maze @game-state-atom)))]

    ;; Join maze
    (swap! game-state-atom join-game new-player)

    (try
      (send-message new-player (format-possible-moves (possible-moves-for-player @game-state-atom new-player)))

      (loop [player new-player]

        ;; Handle Command
        (let [command (receive-message player)
              movement (command-to-movement command)]
          (if movement
            (swap! game-state-atom
                   move-player player movement)
            (send-message player help-text)))

        ;; The player's state has now (probably) changed, due to a move. Look up the current player.
        (let [moved-player (get-in @game-state-atom [:players (:socket player)])]
          (send-message moved-player
                        (format-possible-moves (possible-moves-for-player @game-state-atom moved-player)))

          ;; Sleep
          (Thread/sleep sleep-time-ms)
          (recur moved-player)))
      (catch Exception e
        (swap! game-state-atom leave-game new-player)))))

(def system nil)

(defn start
  "Sets the whole system running."
  [port-number screen-size maze-size]
  (alter-var-root #'system
                  (fn [system]
                    (when (nil? system)
                      (println "Starting up.")

                      (let [game-state-atom (atom (-> (->GameState nil nil nil)
                                                      (new-maze maze-size)
                                                      (merge {:port-number port-number})))]

                        {:network-server (create-network-server game-state-atom
                                                                :port port-number
                                                                :client-handler #'client-handler)

                         :applet (create-applet game-state-atom screen-size)

                         :game-state game-state-atom})))))

(defn stop
  "Stops the whole system."
  []
  (alter-var-root #'system
                  (fn [system]
                    (when system
                      (println "Shutting down." system)
                      (.close (:network-server system))
                      (applet-close (:applet system))
                      nil))))

(defn restart
  "Restarts the whole system."
  [port-number screen-size maze-size]
  (stop)
  (start port-number screen-size maze-size))

(defn -main
  ([] (println "USAGE: lein run <port> [<width> <height>]"))

  ([port-number] (-main port-number "31" "23"))

  ([port-number width height]
     (start (str-to-int port-number)
            [800 600]
            [(str-to-int width)
             (str-to-int height)])))
